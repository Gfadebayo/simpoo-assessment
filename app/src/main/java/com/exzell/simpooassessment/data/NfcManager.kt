@file:OptIn(ExperimentalStdlibApi::class)

package com.exzell.simpooassessment.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import logcat.logcat
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class NfcManager(private val context: Context, private val localRepo: LocalRepository) {
    companion object {
        val ACTION_RESPONSE = "${BuildConfig.APPLICATION_ID}.NfcManager_ACTION_RECEIVED"
        val EXTRA_RESPONSE = "${BuildConfig.APPLICATION_ID}.NfcManager_EXTRA_RESPONSE"
        val EXTRA_MESSAGE = "${BuildConfig.APPLICATION_ID}.NfcManager_EXTRA_MESSAGE"
    }

    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(context) }

    init {
        logcat { "NfcAdapter is $nfcAdapter" }
    }

    suspend fun sendAsHost(content: String) {
        val sendReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                ContextCompat.getMainExecutor(context!!).run {
                    runBlocking {
                        if(intent?.action == ACTION_RESPONSE) {
                            val response = intent.getStringExtra(EXTRA_RESPONSE) ?: return@runBlocking

                            val message = Message(
                                _id = "",
                                who = "xyz",
                                is_by_me = false,
                                body = response,
                                type = MessageType.NFC,
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

        sendReceiver.register(context, ACTION_RESPONSE)

        val intent = Intent(context, HceService::class.java)
            .putExtra(EXTRA_MESSAGE, content)

        context.startService(intent)

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

        localRepo.saveMessage(message)
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

            emit(CommStatus.CONNECTING)

            val isoDep = IsoDep.get(tag)

            if (!isoDep.isConnected) isoDep.connect()

            logcat { "IsoDep connected: ${isoDep.isConnected}" }

            if(!isoDep.isConnected) throw RuntimeException("Could not connect to device")

            emit(CommStatus.CONNECTED)

            isoDep.use {
                val aidApdu = buildSelectApdu("D2760000850101")
                logcat { "AID apdu: ${aidApdu.toHexString()}" }
                val aidResponse = it.transceive(aidApdu)

                if(!aidResponse.contentEquals(byteArrayOf(0x90.toByte(), 0x00))) {
                    val res = it.transceive("D2760000850101".hexToByteArray())

                    if(!res.contentEquals(byteArrayOf(0x90.toByte(), 0x00))) {
                        throw RuntimeException("Could not select the Application AID")
                    }
                }

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

                localRepo.saveMessage(message)

                emit(CommStatus.SENDING)

                val contentBytes = content.encodeToByteArray()
                val apdu = ByteArray(contentBytes.size+1)
                apdu[0] = 0xD2.toByte()
                System.arraycopy(contentBytes, 0, apdu, 1, contentBytes.size)

                val response = it.transceive(apdu)

                localRepo.saveMessage(message.copy(status = SendStatus.SENT), true)

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

enum class CommStatus { SEARCHING, CONNECTING, CONNECTED, SENDING, SENT }