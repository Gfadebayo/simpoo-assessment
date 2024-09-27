package com.exzell.simpooassessment

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.exzell.simpooassessment.core.localRepo
import com.exzell.simpooassessment.data.NfcManager
import com.exzell.simpooassessment.databinding.ActivityNfcBinding
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.ui.utils.viewBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.logcat

class NfcActivity: AppCompatActivity(R.layout.activity_nfc) {

    private val binding by viewBinding { ActivityNfcBinding.bind(it) }

    private val nfcManager by lazy { NfcManager(applicationContext, localRepo) }

    private val snackbar by lazy { Snackbar.make(binding.root, "", Snackbar.LENGTH_INDEFINITE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.apply {

            buttonHost.setOnClickListener {
                val text = layoutChat.textInputMessage.editText?.text?.toString() ?: ""

                if(text.isEmpty()) {
                    snackbar.dismiss()
                    Toast.makeText(this@NfcActivity, "A text must be entered first", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }

                lifecycleScope.launch {
                    nfcManager.sendAsHost(text)
                }
            }

            buttonClient.setOnClickListener {
                val text = layoutChat.textInputMessage.editText?.text?.toString() ?: ""

                if(text.isEmpty()) {
                    snackbar.dismiss()
                    Toast.makeText(this@NfcActivity, "A text must be entered first", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }

                nfcManager.sendAsReader(text, this@NfcActivity)
                    .flowWithLifecycle(lifecycle)
                    .onEach { snackbar.setText(it.name).show() }
                    .launchIn(lifecycleScope)
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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logcat { "New intent received: ${intent}" }
    }
}