package com.exzell.simpooassessment.data

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony.Sms.Intents
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.exzell.simpooassessment.local.LocalRepository
import com.exzell.simpooassessment.local.Message
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.local.model.SendStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.logcat

object SmsManager {

    const val ACTION_SEND = "send"
    const val ACTION_DELIVER = "deliver"

    private val Context.smsManager: SmsManager
        get() = ContextCompat.getSystemService(this, SmsManager::class.java)
            ?: throw RuntimeException("SMS Manager could not be retrieved")

    fun watchIncomingSms(context: Context, localRepo: LocalRepository) {
        val smsReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                logcat { "Message received with intent $intent" }

                ContextCompat.getMainExecutor(context!!).execute {
                    runBlocking {
                        val body = Intents.getMessagesFromIntent(intent)

                        body.forEach {
                            launch {
                                val message = Message(
                                    _id = "",
                                    who = it.originatingAddress ?: it.displayOriginatingAddress,
                                    body = it.displayMessageBody,
                                    is_by_me = false,
                                    type = MessageType.SMS,
                                    status = SendStatus.SENT,
                                    created_at = 0,
                                    updated_at = 0
                                )

                                localRepo.saveMessage(message)
                            }
                        }
                    }
                }
            }
        }

        smsReceiver.register(context, Intents.SMS_RECEIVED_ACTION, isExported = true)
    }

    suspend fun sendSMS(
        context: Context,
        phone: String,
        message: String,
        localRepo: LocalRepository
    ) {
        val localMessage = Message(
            _id = "",
            who = phone,
            body = message,
            is_by_me = true,
            type = MessageType.SMS,
            status = SendStatus.SENDING,
            created_at = -1,
            updated_at = -1,
        )

        val smsManager = context.smsManager

        logcat { "Ready to send sms" }

        val sendReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                logcat { "Result code $resultCode" }
                ContextCompat.getMainExecutor(context!!).execute {
                    runBlocking {
                        val status = if(resultCode == Activity.RESULT_OK) SendStatus.SENDING
                        else SendStatus.FAIL

                        localRepo.saveMessage(localMessage.copy(status = status), update = true)
                    }
                }

                when (resultCode) {
                    Activity.RESULT_OK -> {
                        logcat { "SMS sent" }
                    }

                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                        logcat { "Generic error failure" }
                    }
                    SmsManager.RESULT_ERROR_NO_SERVICE -> {
                        logcat { "Error no service" }
                    }
                    SmsManager.RESULT_ERROR_NULL_PDU -> {
                        logcat { "Error null pdu" }
                    }
                    SmsManager.RESULT_ERROR_RADIO_OFF -> {
                        logcat { "Error Radio off" }
                    }
                }
            }
        }

        val deliverReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                ContextCompat.getMainExecutor(context!!).execute {
                    runBlocking {
                        val status = if(resultCode == Activity.RESULT_OK) SendStatus.SENT
                        else SendStatus.FAIL

                        localRepo.saveMessage(localMessage.copy(status = status), update = true)

                        when (resultCode) {
                            Activity.RESULT_OK -> {
                                logcat { "SMS delivered" }
                            }
                            Activity.RESULT_CANCELED -> {

                            }
                        }
                    }
                }
            }
        }

        sendReceiver.register(context, ACTION_SEND, isExported = true)

        deliverReceiver.register(context, ACTION_DELIVER, isExported = true)

        val sendPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_SEND),
            PendingIntent.FLAG_IMMUTABLE
        )

        val deliverPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_DELIVER),
            PendingIntent.FLAG_IMMUTABLE
        )

        smsManager.sendTextMessage(phone, null, message, sendPendingIntent, deliverPendingIntent)

        localRepo.saveMessage(localMessage)
    }
}

enum class SmsStatus { SENDING, DELIVERED, FAILED }

data class SmsMessage(
    val phone: String,
    val body: String,
    val status: SmsStatus
)