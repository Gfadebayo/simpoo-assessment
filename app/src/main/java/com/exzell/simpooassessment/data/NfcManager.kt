@file:OptIn(ExperimentalStdlibApi::class)

package com.exzell.simpooassessment.data

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.TagTechnology
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.util.Consumer
import com.exzell.simpooassessment.BuildConfig
import com.exzell.simpooassessment.HceService
import com.exzell.simpooassessment.local.LocalRepository
import com.exzell.simpooassessment.local.Message
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.local.model.SendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import logcat.logcat
import java.lang.Exception
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class NfcManager(private val context: Context, private val localRepo: LocalRepository) {
    companion object {
        val ACTION_RESPONSE = "${BuildConfig.APPLICATION_ID}.NfcManager_ACTION_RECEIVED"
        val EXTRA_STATUS = "${BuildConfig.APPLICATION_ID}.NfcManager_EXTRA_STATUS"
        val EXTRA_RESPONSE = "${BuildConfig.APPLICATION_ID}.NfcManager_EXTRA_RESPONSE"
        val EXTRA_MESSAGE = "${BuildConfig.APPLICATION_ID}.NfcManager_EXTRA_MESSAGE"
    }

    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(context) }

    init {
        logcat { "NfcAdapter is $nfcAdapter" }
    }

    val isSupported: Boolean
        get() = nfcAdapter != null

    val isTurnedOn: Boolean
        get() = nfcAdapter != null && nfcAdapter.isEnabled

    val isSupportHce: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

    private val _statusFlow = MutableStateFlow<CommStatus?>(null)
    val statusFlow = _statusFlow.asStateFlow()

    fun turnOn(activity: Activity): Boolean {
        return try {
            val action = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_NFC
            else Settings.ACTION_NFC_SETTINGS

            activity.startActivityForResult(Intent(action), 12)
            true
        }
        catch (e: Exception) {
            logcat { e.stackTraceToString() }
            false
        }
    }

    suspend fun sendAsTag(content: String) {
        _statusFlow.value = CommStatus.WAITING

        val hceIntent = Intent(context, HceService::class.java)
            .putExtra(EXTRA_MESSAGE, content)

        val message = Message(
            _id = "",
            who = "xyz",
            is_by_me = true,
            body = content,
            type = MessageType.NFC,
            status = SendStatus.SENDING,
            created_at = 0,
            updated_at = 0
        )

        val id = localRepo.saveMessage(message)

        val sendReceiver = object: BroadcastReceiver() {
            override fun onReceive(contextt: Context?, intent: Intent?) {
                val me = this
                ContextCompat.getMainExecutor(context).run {
                    runBlocking {
                        if(intent?.action == ACTION_RESPONSE) {
                            intent.getStringExtra(EXTRA_STATUS)?.also {
                                val status = CommStatus.valueOf(it)

                                _statusFlow.value = status

                                if(status == CommStatus.SENT) {
                                    localRepo.updateStatus(SendStatus.SENT, id = id)
                                }
                                else if(status == CommStatus.SEND_FAIL) {
                                    localRepo.updateStatus(SendStatus.FAIL, id = id)
                                }

                                if(status == CommStatus.DISCONNECTED) {
                                    me.unregister(context)
                                    context.stopService(hceIntent)
                                }
                            }

                            val response = intent.getStringExtra(EXTRA_RESPONSE) ?: return@runBlocking

                            val responseMessage = Message(
                                _id = "",
                                who = message.who,
                                is_by_me = false,
                                body = response,
                                type = MessageType.NFC,
                                status = SendStatus.SENT,
                                created_at = 0,
                                updated_at = 0
                            )

                            localRepo.saveMessage(responseMessage)
                        }
                    }
                }
            }
        }

        sendReceiver.register(context, ACTION_RESPONSE, isExported = true)

        context.startService(hceIntent)
    }

    fun sendAsReader(content: String, activity: AppCompatActivity): Flow<CommStatus> {
        return flow {
            emit(CommStatus.SEARCHING)

            val tag = suspendCoroutine<Tag?> { cont ->
                val newIntentListener = object: Consumer<Intent> {
                    override fun accept(value: Intent) {
                        logcat { "newIntentListener called and $value" }
                        if (value.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
                            activity.removeOnNewIntentListener(this)
                            val intent = IntentCompat.getParcelableExtra(value, NfcAdapter.EXTRA_TAG, Tag::class.java)
                            cont.resume(intent)
                        }
                    }
                }

                activity.addOnNewIntentListener(newIntentListener)


                val intent: Intent = Intent(context, activity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP and Intent.FLAG_ACTIVITY_NEW_TASK)

                val pendingIntent = PendingIntentCompat.getActivity(context, 0, intent, 0, true)
                nfcAdapter.enableForegroundDispatch(activity, pendingIntent, null, null)

                logcat { "Setting up lifecycle event monitor" }

//                activity.lifecycle.eventFlow
//                    .onEach {
//                        if(it == Lifecycle.Event.ON_PAUSE) {
//                            logcat { "Lifecycle event reached: $it and intent: ${activity.intent}" }
////                            nfcAdapter.disableForegroundDispatch(activity)
////                            activity.removeOnNewIntentListener(newIntentListener)
////                            cont.resume(null)
//                        }
//                    }
//                    .launchIn(activity.lifecycleScope)
            }

            logcat { "Tag with id found: ${tag?.id?.toHexString() ?: "null"}" }
            logcat { "Tag with techList: ${tag?.techList?.joinToString()}" }

            if(tag == null) throw RuntimeException("Tag is null")

            val message = Message(
                _id = "",
                who = tag.id.toHexString(),
                is_by_me = true,
                body = content,
                type = MessageType.NFC,
                status = SendStatus.SENDING,
                created_at = 0,
                updated_at = 0
            )

            val id = localRepo.saveMessage(message)

            emit(CommStatus.CONNECTING)

            val isoDep = NfcA.get(tag)

            if (!isoDep.isConnected) isoDep.connect()

            logcat { "IsoDep connected: ${isoDep.isConnected}" }

            if(!isoDep.isConnected) throw RuntimeException("Could not connect to device")

            emit(CommStatus.CONNECTED)

            isoDep.use {
                //The very first thing to do is to send an APDU to notify the device of the service
                //subsequent APDU calls should be forwarded to
                val idApdu = "D2760000850101".hexToByteArray()
                val result = isoDep.transceive(idApdu)
                logcat { "System select command returns: ${result.toHexString()}" }
                if(!result.contentEquals("6E00".hexToByteArray())) {
                    emit(CommStatus.SEND_FAIL)
                    localRepo.updateStatus(SendStatus.FAIL, id)
                    return@use
                }

                //Next send an APDU to select the same AID to the system selected service
                val aidApdu = buildSelectApdu("D2760000850101")
                val aidResponse = it.transceive(aidApdu)
                logcat { "AID apdu: ${aidResponse.toHexString()}" }

                if(!aidResponse.contentEquals("9000".hexToByteArray())) {
                    emit(CommStatus.SEND_FAIL)
                    localRepo.updateStatus(SendStatus.FAIL, id)
                    return@use
                }

                emit(CommStatus.SENDING)

                val contentBytes = content.encodeToByteArray()
                val apdu = ByteArray(contentBytes.size+1)
                apdu[0] = 0xD2.toByte()
                System.arraycopy(contentBytes, 0, apdu, 1, contentBytes.size)

                val response = it.transceive(apdu)

                localRepo.updateStatus(SendStatus.SENT, id)

                if(response[0] == 0xD2.toByte()) logcat { "Sent success" }

                val responseContent = response.drop(1).toByteArray()

                val responseMessage = Message(
                    _id = "",
                    who = tag.id.toHexString(),
                    is_by_me = false,
                    body = String(responseContent),
                    type = MessageType.NFC,
                    status = SendStatus.SENT,
                    created_at = 0,
                    updated_at = 0
                )

                localRepo.saveMessage(responseMessage)

                emit(CommStatus.SENT)
            }

            emit(CommStatus.DISCONNECTED)
        }.flowOn(Dispatchers.IO)
            .onCompletion {
            logcat { "Disabling nfc watch" }
            nfcAdapter.disableForegroundDispatch(activity)
        }
    }
}

private const val SELECT_APDU_HEADER = "00A40400"
const val GET_DATA_APDU_HEADER = "00CA0000"

/**
 * Build APDU for SELECT AID command. This command indicates which service a reader is
 * interested in communicating with. See ISO 7816-4.
 *
 * @param aid Application ID (AID) to select
 * @return APDU for SELECT AID command
 */
fun buildSelectApdu(aid: String): ByteArray {
    // Format: [CLASS | INSTRUCTION | PARAMETER 1 | PARAMETER 2 | LENGTH | DATA]

    return (SELECT_APDU_HEADER + String.format("%02X", aid.length / 2) + aid).hexToByteArray()
}

/**
 * Build APDU for GET_DATA command. See ISO 7816-4.
 *
 * @return APDU for SELECT AID command
 */
fun buildGetDataApdu(): ByteArray {
    // Format: [CLASS | INSTRUCTION | PARAMETER 1 | PARAMETER 2 | LENGTH | DATA]
    return (GET_DATA_APDU_HEADER + "0FFF").hexToByteArray()
}

enum class CommStatus { WAITING, SEARCHING, CONNECTING, CONNECTED, DISCONNECTED, SENDING, SENT, SEND_FAIL }