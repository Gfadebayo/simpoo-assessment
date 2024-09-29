package com.exzell.simpooassessment

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.exzell.simpooassessment.core.localRepo
import com.exzell.simpooassessment.data.BluetoothConnect
import com.exzell.simpooassessment.data.BluetoothManager
import com.exzell.simpooassessment.data.isBelowS
import com.exzell.simpooassessment.data.isBluetoothOn
import com.exzell.simpooassessment.databinding.ActivityBluetoothBinding
import com.exzell.simpooassessment.databinding.ItemContactBinding
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.ui.utils.viewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

class BluetoothActivity: AppCompatActivity(R.layout.activity_bluetooth) {
    private val TIMEOUT = 3600

    private val binding by viewBinding { ActivityBluetoothBinding.bind(it) }

    private val connector by lazy { BluetoothManager(applicationContext, localRepo, lifecycleScope) }

    private lateinit var bluetoothOnLauncher: ActivityResultLauncher<Intent>

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var discoverLauncher: ActivityResultLauncher<Intent>

    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(binding.toolbar)
        setupBtLaunchers()
        binding.apply {
            buttonNew.setOnClickListener {
                recyclerContact.isVisible = false
                textBanner.text = "Creating and Listening for Bluetooth devices"

                connector.createSocket()
            }

            buttonSearch.setOnClickListener {
                recyclerContact.isVisible = true
                beginSearch()
            }

            layoutChat.buttonSend.setOnClickListener {
                val text = layoutChat.textInputMessage.editText?.text?.toString() ?: ""
                connector.send(text)
            }

            recyclerContact.adapter = ContactAdapter().apply {
                onClick = {
                    textBanner.text = "Connecting to Bluetooth device"
                    connector.connect(it.id)
                }
            }

            layoutChat.recyclerChat.adapter = ChatAdapter()

            localRepo.getMessagesForType(MessageType.BT)
                .map { it.map { message -> Chat(message._id, message.body, message.status.name, message.is_by_me) } }
                .onEach { (layoutChat.recyclerChat.adapter as ChatAdapter).submitList(it) }
                .launchIn(lifecycleScope)

            connector.connectFlow
                .onEach {
                    layoutChat.root.isVisible = it
                    textBanner.text = if(it) "Connected to BT Device" else "Disconnected from BT Device"
                }
                .launchIn(lifecycleScope)

            recyclerContact.isVisible = false
            layoutChat.root.isVisible = false

            requestPermission()
        }
    }

    private fun setupBtLaunchers() {
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if(it.all { (_, value) -> value }) makeDiscover()
            else {
                Toast.makeText(this@BluetoothActivity, "Permission required", Toast.LENGTH_SHORT).show()
            }
        }

        bluetoothOnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) makeDiscover()
            else if (it.resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this@BluetoothActivity, "Bluetooth denied", Toast.LENGTH_SHORT).show()
            }
        }

        discoverLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK || it.resultCode == TIMEOUT) beginSearch()
            else if (it.resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this@BluetoothActivity, "Discovery denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun beginSearch() {
        searchJob?.cancel()

        if(!binding.recyclerContact.isVisible) return

        searchJob = connector.searchForDevices()
            .onStart { binding.textBanner.text = "Searching for Bluetooth devices" }
            .catch {
                if(it.message == BluetoothManager.ERROR_SEARCH_COMPLETE) {
                    binding.textBanner.text = "Bluetooth device search complete"
                }
            }
            .map { it.map { bt -> Contact(bt.address, bt.name) } }
            .onEach {
                (binding.recyclerContact.adapter as ContactAdapter).submitList(it)
            }
            .launchIn(lifecycleScope)
    }

    private fun requestPermission() {
        val permissions = buildList {
            add(Manifest.permission.BLUETOOTH_ADMIN)

            if (!isBelowS) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            else {
                add(Manifest.permission.BLUETOOTH)
            }

        }.toTypedArray()

        permissionLauncher.launch(permissions)
    }

    private fun makeDiscover() {
        fun turnOnBluetooth() {
            bluetoothOnLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }

        val result = isBluetoothOn()

        when (result) {
            BluetoothConnect.NO_PERMISSION -> requestPermission()
            BluetoothConnect.UNSUPPORTED -> {
                Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            }
            BluetoothConnect.OFF -> turnOnBluetooth()
            BluetoothConnect.ON -> {
                if(!connector.isDiscoverable) {
                    val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, TIMEOUT)

                    discoverLauncher.launch(discoverableIntent)
                }
                else beginSearch()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connector.close()
    }
}

data class Contact(val id: String, val name: String)

class ContactAdapter: ListAdapter<Contact, ContactAdapter.ViewHolder>(DIFF_UTIL) {
    companion object {
        val DIFF_UTIL = object: DiffUtil.ItemCallback<Contact>() {
            override fun areItemsTheSame(oldItem: Contact, newItem: Contact): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Contact, newItem: Contact): Boolean {
                return oldItem == newItem
            }
        }
    }

    var onClick: ((Contact) -> Unit) = {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false).run {
            ViewHolder(this)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.apply {
            name.text = currentList[position].name
        }
    }

    inner class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val binding = ItemContactBinding.bind(view).apply {
            name.setOnClickListener { onClick(currentList[bindingAdapterPosition]) }
        }
    }
}