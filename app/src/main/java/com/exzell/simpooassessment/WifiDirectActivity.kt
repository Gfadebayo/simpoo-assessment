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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.logcat

class WifiDirectActivity: AppCompatActivity(R.layout.activity_wifi) {

    private val binding by viewBinding { ActivityWifiBinding.bind(it) }

    private lateinit var scanLauncher: ActivityResultLauncher<ScanOptions>

    private val wifiManager by lazy { WifiManager(applicationContext, localRepo, lifecycleScope) }

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
                    textBanner.text = connected?.translate()
                    imageView.isVisible = connected != WifiManager.Status.CONNECTED
                    layoutChat.root.isVisible = connected == WifiManager.Status.CONNECTED
                }
                .launchIn(lifecycleScope)

            wifiManager.connectionFlow
                .map { it.second }
                .flatMapLatest { localRepo.getMessageBySenderAndType(it, MessageType.WIFI) }
                .map { it.map { message -> Chat(message._id, message.body, message.status.name, message.is_by_me) } }
                .onEach { (layoutChat.recyclerChat.adapter as ChatAdapter).submitList(it) }
                .launchIn(lifecycleScope)

            if(!wifiManager.isTurnedOn) {
                Snackbar.make(root, "WiFi is not turned on, please turn it on to proceed", Snackbar.LENGTH_INDEFINITE).also {
                    it.setAction("Turn On") { _ ->
                        if(!wifiManager.turnOn(this@WifiDirectActivity)) {
                            Toast.makeText(this@WifiDirectActivity, "WiFi cannot be turned on, it does not exist in settings", Toast.LENGTH_SHORT)
                                .show()
                        }

                        it.dismiss()
                    }
                    it.show()
                }
            }
        }
    }

    private fun WifiManager.Status.translate(): String {
        return when(this) {
            WifiManager.Status.CREATING -> "Creating QR code"
            WifiManager.Status.WAITING -> "Waiting for client to join"
            WifiManager.Status.JOINING -> "Joining WiFi group"
            WifiManager.Status.CONNECTED -> "Connected to WiFi direct device"
            WifiManager.Status.DISCONNECTED -> "Disconnected"
        }
    }

    override fun onResume() {
        super.onResume()
        binding.apply {
            if(wifiManager.isTurnedOn && !wifiManager.isSupported) {
                textBanner.text = "WiFi Direct is not supported by this device. Legacy WiFi will be used instead but in this case, this device cannot create a QR code, it can only join"
                buttonNew.isEnabled = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logcat { "Destroying this thing" }
        runBlocking(Dispatchers.IO) {
            wifiManager.stop()
        }
    }
}