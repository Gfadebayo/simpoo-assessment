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
import com.exzell.simpooassessment.databinding.ActivityBluetoothBinding
import com.exzell.simpooassessment.databinding.ItemContactBinding
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.ui.utils.viewBinding
import com.exzell.simpooassessment.data.BluetoothConnector
import com.exzell.simpooassessment.data.isBelowS
import com.exzell.simpooassessment.data.isBluetoothOn
import com.exzell.simpooassessment.local.Message
import com.exzell.simpooassessment.local.model.SendStatus
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.logcat

class BluetoothActivity: AppCompatActivity(R.layout.activity_bluetooth) {

    private val binding by viewBinding { ActivityBluetoothBinding.bind(it) }

    private val connector = BluetoothConnector

    private lateinit var bluetoothOnLauncher: ActivityResultLauncher<Intent>

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private fun requestPermission() {
        val permissions = buildList {
            add(Manifest.permission.BLUETOOTH)

            add(Manifest.permission.BLUETOOTH_ADMIN)

            if (!isBelowS) {
                add(Manifest.permission.BLUETOOTH_SCAN)

                add(Manifest.permission.BLUETOOTH_CONNECT)
            }

        }.toTypedArray()

        permissionLauncher.launch(permissions)
    }

    private var searchJob: Job? = null

    private fun beginSearch() {
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
                searchJob?.cancel()

                searchJob = BluetoothConnector.searchForDevices(applicationContext)
                    .map { it.map { bt -> Contact(bt.address, bt.name) } }
                    .onEach {
                        (binding.recyclerContact.adapter as ContactAdapter).submitList(it)
                    }
                    .launchIn(lifecycleScope)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(binding.toolbar)
        binding.apply {
            val contract = ActivityResultContracts.RequestMultiplePermissions()
            permissionLauncher = registerForActivityResult(contract) {
                if(it.all { (_, value) -> value }) beginSearch()
            }

            bluetoothOnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (it.resultCode == Activity.RESULT_OK) beginSearch()
                else if (it.resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this@BluetoothActivity, "Bluetooth denied", Toast.LENGTH_SHORT).show()
                }
            }

            buttonNew.setOnClickListener {
                lifecycleScope.launch {
                    logcat { "Listening for incoming connections" }
                    showChat(null)
                }
            }

            recyclerContact.adapter = ContactAdapter().apply {
                onClick = {
                    showChat(it)
                }
            }

            layoutChat.recyclerChat.adapter = ChatAdapter()

            localRepo.getMessagesForType(MessageType.BT)
                .map { it.map { message -> Chat(message._id, message.body, message.status.name, message.is_by_me) } }
                .onEach { (layoutChat.recyclerChat.adapter as ChatAdapter).submitList(it) }
                .launchIn(lifecycleScope)

            recyclerContact.isVisible = true
            layoutChat.root.isVisible = false

            snackBar.setText("Showing currently paired devices").show()

            requestPermission()
        }
    }

    private val snackBar by lazy { Snackbar.make(binding.root, "", Snackbar.LENGTH_INDEFINITE) }

    private fun showChat(contact: Contact?) {
        binding.layoutChat.apply {
            lifecycleScope.launch {
                val text = if(contact == null) "Creating a new socket connection" else "Connecting to ${contact.id}"

                snackBar.setText(text).show()

                val (id, socket) = if(contact!= null) contact.id to connector.bondAndConnect(applicationContext, contact.id)
                else connector.createSocket(applicationContext)

                val statusText = if(socket == null) "Failed to connect to socket" else "Connected"
                Toast.makeText(this@BluetoothActivity, statusText, Toast.LENGTH_SHORT).show()

                root.isVisible = socket != null

                buttonSend.setOnClickListener {
                    lifecycleScope.launch {
                        val text = textInputMessage.editText?.text?.toString() ?: return@launch
                        val file = BluetoothConnector.BtFile(
                            "Simple file",
                            "text/plain",
                            text.encodeToByteArray()
                        )

                        connector.sendBytes(socket!!, file).collect()

                        val message = Message(
                            _id = "",
                            who = id,
                            is_by_me = true,
                            body = text,
                            type = MessageType.BT,
                            status = SendStatus.SENT,
                            created_at = 0,
                            updated_at = 0
                        )

                        localRepo.saveMessage(message)
                    }
                }

                lifecycleScope.launch {
                    if(socket != null) connector.listen(id, socket, localRepo)
                }
            }
        }
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