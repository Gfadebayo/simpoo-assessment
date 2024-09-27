package com.exzell.simpooassessment

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.exzell.simpooassessment.core.localRepo
import com.exzell.simpooassessment.data.WifiManager
import com.exzell.simpooassessment.databinding.ActivityWifiBinding
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.ui.utils.viewBinding
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.logcat

class WifiDirectActivity: AppCompatActivity(R.layout.activity_wifi) {

    private val binding by viewBinding { ActivityWifiBinding.bind(it) }

    private lateinit var scanLauncher: ActivityResultLauncher<ScanOptions>

    private val wifiManager by lazy { WifiManager(applicationContext, localRepo, lifecycleScope) }

    private val snackbar by lazy { Snackbar.make(binding.root, "", Snackbar.LENGTH_INDEFINITE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanLauncher = registerForActivityResult(ScanContract()) {
            lifecycleScope.launch {
                if(!it.contents.isNullOrEmpty()) {
                    if(!wifiManager.connectUsingQr(it.contents)) {
                        Toast.makeText(this@WifiDirectActivity, "WiFi not enabled, Turn it on", Toast.LENGTH_SHORT).show()
                    }
//                    val text = if(connected) "Connected" else "Not Connected"
//                    snackbar.setText(text).show()
                }
            }
        }

        binding.apply {
            setSupportActionBar(toolbar)

            buttonNew.setOnClickListener {
                lifecycleScope.launch {
                    val bitmap = wifiManager.createQrCode()
                    if(bitmap != null) imageView.setImageBitmap(bitmap)
                    else {
                        Toast.makeText(this@WifiDirectActivity, "Unable to create QR, Check if WiFi is turned on", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            buttonJoin.setOnClickListener {
                scanLauncher.launch(ScanOptions())
            }

            layoutChat.apply {
                recyclerChat.adapter = ChatAdapter()

                buttonSend.setOnClickListener {
                    val body = textInputMessage.editText?.text.toString()

                    wifiManager.sendBytes(body)
                }
            }

            wifiManager.connectionFlow
                .onEach { (connected, _) ->
                    val text  = if(connected) "Connected" else "Not connected"

                    Toast.makeText(this@WifiDirectActivity, text, Toast.LENGTH_SHORT).show()

                    if (connected) {
                        imageView.isVisible = false
                        layoutChat.root.isVisible = true
                    }
                }
                .launchIn(lifecycleScope)

            wifiManager.connectionFlow
                .map { it.second }
                .flatMapLatest { localRepo.getMessageBySenderAndType(it, MessageType.WIFI) }
                .map { it.map { message -> Chat(message._id, message.body, message.status.name, message.is_by_me) } }
                .onEach { (layoutChat.recyclerChat.adapter as ChatAdapter).submitList(it) }
                .launchIn(lifecycleScope)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiManager.stop()
    }
}