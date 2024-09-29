package com.exzell.simpooassessment

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.exzell.simpooassessment.core.localRepo
import com.exzell.simpooassessment.data.CommStatus
import com.exzell.simpooassessment.data.NfcManager
import com.exzell.simpooassessment.databinding.ActivityNfcBinding
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.ui.utils.viewBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.logcat

class NfcActivity: AppCompatActivity(R.layout.activity_nfc) {

    private val binding by viewBinding { ActivityNfcBinding.bind(it) }

    private val nfcManager by lazy { NfcManager(applicationContext, localRepo) }

    private var job: Job? = null

    private val bannerBgNormal = 0xA6A1A1

    private val bannerBgError = 0xFF0303

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.apply {
            buttonTag.setOnClickListener {
                if(nfcManager.isSupportHce) {
                    val text = layoutChat.textInputMessage.editText?.text?.toString() ?: ""
                    beginAsTag(text)
                }
                else {
                    Toast.makeText(this@NfcActivity, "This device cannot function as an NFC tag", Toast.LENGTH_SHORT)
                        .show()
                }
            }

            buttonReader.setOnClickListener {
                val text = layoutChat.textInputMessage.editText?.text?.toString() ?: ""
                beginAsReader(text)
            }

            layoutChat.apply {
                root.isVisible = true
                recyclerChat.adapter = ChatAdapter()
            }

            localRepo.getMessagesForType(MessageType.NFC)
                .onEach { logcat { "Messages are: ${it.joinToString(separator = "\n")}" } }
                .map { it.map { message -> Chat(message._id, message.body, message.status.name, message.is_by_me) } }
                .onEach {
                    (layoutChat.recyclerChat.adapter as ChatAdapter).submitList(it)
                }
                .launchIn(lifecycleScope)

            nfcManager.statusFlow
                .onEach { binding.textBanner.text = it?.translate() }
                .launchIn(lifecycleScope)

            if(!nfcManager.isSupported) {
                textBanner.setBackgroundColor(Color.RED)
                textBanner.text = "NFC is not supported on this device"
            }

            if(!nfcManager.isTurnedOn && nfcManager.isSupported) {
                Snackbar.make(root, "NFC is not turned on, please turn it on to proceed", Snackbar.LENGTH_INDEFINITE).also {
                    it.setAction("Turn On") { _ ->
                        if(!nfcManager.turnOn(this@NfcActivity)) {
                            Toast.makeText(this@NfcActivity, "NFC cannot be turned on, it does not exist in settings", Toast.LENGTH_SHORT)
                                .show()
                        }

                        it.dismiss()
                    }
                    it.show()
                }
            }
        }
    }

    private fun beginAsTag(text: String) {
        binding.apply {
            if(text.isEmpty()) {
                textBanner.text = null
                Toast.makeText(this@NfcActivity, "A text must be entered first", Toast.LENGTH_SHORT)
                    .show()
                return
            }

            lifecycleScope.launch {
                nfcManager.sendAsTag(text)
            }
        }
    }

    private fun beginAsReader(text: String) {
        binding.apply {
            if(text.isEmpty()) {
                textBanner.text = null
                Toast.makeText(this@NfcActivity, "A text must be entered first", Toast.LENGTH_SHORT)
                    .show()
                return
            }

            job?.cancel()

            job = nfcManager.sendAsReader(text, this@NfcActivity)
                .flowWithLifecycle(lifecycle)
                .onEach { textBanner.text = it.name }
                .launchIn(lifecycleScope)
        }
    }

    private fun CommStatus.translate(): String {
        return when(this) {
            CommStatus.WAITING -> "Waiting for Reader"
            CommStatus.SEARCHING -> "Waiting for Tag"
            CommStatus.CONNECTING -> "Connecting to Tag"
            CommStatus.CONNECTED -> "Connected to Tag"
            CommStatus.SENDING -> "Sending message"
            CommStatus.SENT -> "Message delivered!"
            CommStatus.DISCONNECTED -> "Disconnected"
            CommStatus.SEND_FAIL -> "Unable to send message"
        }
    }
}