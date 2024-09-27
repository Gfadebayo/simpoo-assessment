package com.exzell.simpooassessment

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.exzell.simpooassessment.databinding.ActivityMainBinding
import com.exzell.simpooassessment.ui.utils.viewBinding

class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private val binding by viewBinding { ActivityMainBinding.bind(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding.apply {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {

            }.launch(
                arrayOf(
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                )
            )

            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }

            buttonSms.setOnClickListener {
                startActivity(Intent(this@MainActivity, SmsActivity::class.java))
            }

            buttonBluetooth.setOnClickListener {
                startActivity(Intent(this@MainActivity, BluetoothActivity::class.java))
            }

            buttonWifi.setOnClickListener {
                startActivity(Intent(this@MainActivity, WifiDirectActivity::class.java))
            }

            buttonNfc.setOnClickListener {
                startActivity(Intent(this@MainActivity, NfcActivity::class.java))
            }
        }
    }
}