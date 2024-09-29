package com.exzell.simpooassessment

import android.Manifest
import android.content.Intent
import android.os.Build
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
                buildList {
                    add(Manifest.permission.SEND_SMS)
                    add(Manifest.permission.READ_SMS)
                    add(Manifest.permission.RECEIVE_SMS)

                    add(Manifest.permission.BLUETOOTH_ADMIN)
                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(Manifest.permission.BLUETOOTH_SCAN)
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                        add(Manifest.permission.BLUETOOTH_ADVERTISE)
                    }
                    else {
                        add(Manifest.permission.BLUETOOTH)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
//                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }

                    add(Manifest.permission.ACCESS_WIFI_STATE)
                    add(Manifest.permission.CHANGE_WIFI_STATE)
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }.toTypedArray()
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