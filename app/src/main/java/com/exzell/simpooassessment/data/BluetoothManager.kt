package com.exzell.simpooassessment.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.exzell.simpooassessment.local.LocalRepository
import com.exzell.simpooassessment.local.Message
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.local.model.SendStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@SuppressLint("MissingPermission")
class BluetoothManager(
    private val context: Context,
    private val localRepo: LocalRepository,
    private val scope: CoroutineScope
) {
    companion object {
        val ERROR_SEARCH_COMPLETE = "search complete"
        private val OBEX_UUID = UUID.fromString("d51e2d9f-4846-4963-b3cc-ae6d7b0bf8b5")
    }

    val adapter by lazy {
        val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)!!
        manager.adapter
    }

    val isDiscoverable: Boolean
        get() = adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE

    private val _connectFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val connectFlow = _connectFlow.asSharedFlow()

    private var socket: BluetoothSocket? = null
        set(value) {
            try {
                _connectFlow.tryEmit(false)
                logcat(priority = LogPriority.ERROR) { "Attempting to close previous socket: ${field}" }
                field?.close()
            }
            catch(e: Exception) { }

            field = value
            if(value != null) {
                _connectFlow.tryEmit(true)
                scope.launch { listen(value) }
            }
        }

    private var serverSocket: BluetoothServerSocket? = null
        set(value) {
            try {
                logcat(LogPriority.ERROR) { "Attempting to close previously created server socket: ${field}" }
                field?.close()
            }
            catch(e: Exception) { }

            field = value
            if(value != null) {
                scope.launch(Dispatchers.IO) { socket = value.accept() }
            }
        }

    private val disconnectReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.also {
                if(it.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                    _connectFlow.tryEmit(false)
                    serverSocket = null
                    socket = null
                }
            }
        }
    }

    init {
        disconnectReceiver.register(context, BluetoothDevice.ACTION_ACL_DISCONNECTED, true)
    }

    fun searchForDevices(): Flow<List<BluetoothDevice>> = callbackFlow {
            val total = mutableSetOf<BluetoothDevice>()

            if(!context.hasPermission(
                    if(!isBelowS) Manifest.permission.BLUETOOTH_SCAN else "",
                    if(!isBelowS) Manifest.permission.BLUETOOTH_CONNECT else ""
                )){
                close(IllegalStateException("Required permissions not granted"))
            }

            val bluetoothReceiver = object: BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    logcat { "Bluetooth device found $intent" }

                    if (intent.action == BluetoothDevice.ACTION_FOUND) {
                        IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.also {
                            if (it.address == null || it.name == null) return

                            total.add(it)
                            trySend(total.toList())
                        }
                    }
                }
            }

        val discoverReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                logcat { "Discovery finished" }
                if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                    close(Exception(ERROR_SEARCH_COMPLETE))
                }
            }
        }

        bluetoothReceiver.register(context, BluetoothDevice.ACTION_FOUND, isExported = true)

        discoverReceiver.register(context, BluetoothAdapter.ACTION_DISCOVERY_FINISHED, isExported = true)

        val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)!!
            val adapter = manager.adapter!!

            if(!adapter.isDiscovering) {
                adapter.startDiscovery()
            }

        total.addAll(adapter.bondedDevices)
        trySend(total.toList())

            awaitClose {
                logcat { "Cancelling search" }
                try {
                    adapter.cancelDiscovery()
                    context.unregisterReceiver(bluetoothReceiver)
                }
                catch(e: Exception) {
                    logcat { e.stackTraceToString() }
                }

                try {
                    context.unregisterReceiver(discoverReceiver)
                }
                catch(e: Exception) {
                    logcat { e.stackTraceToString() }
                }
            }
        }

     fun connect(deviceID: String) {
         adapter.cancelDiscovery()

         val device = adapter.getRemoteDevice(deviceID)

        scope.launch {
            if(bond(context, device)) {
                val uuids = listOf(OBEX_UUID)//getUUids()

                logcat { "UUIDS\n${uuids.joinToString(separator = "\n")}" }

                for(uuid in uuids) {
                    val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)

                    try {
                        socket.connect()
                        this@BluetoothManager.socket = socket
                        break
                    }
                    catch(e: Exception) {
                        logcat { e.stackTraceToString() }
                        continue
                    }
                }
            }
        }
    }

    fun createSocket() {
        scope.launch {
            adapter.cancelDiscovery()

            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("Simpoo", OBEX_UUID)
        }
    }

    fun send(content: String) {
        scope.launch {
            socket?.apply {
                if(!isConnected) {
                    _connectFlow.emit(false)
                    return@launch
                }

                if(content.isBlank()) return@launch

                val id = remoteDevice.address

                val message = Message(
                    _id = "",
                    who = id,
                    is_by_me = true,
                    body = content,
                    type = MessageType.BT,
                    status = SendStatus.SENDING,
                    created_at = 0,
                    updated_at = 0
                )

                val messageId = localRepo.saveMessage(message)

                logcat { "inside sendBytes thing and socket state is ${isConnected}" }

                outputStream.also {
                    try {
                        val bytes = content.encodeToByteArray()

                        for ((offset, b) in bytes.withIndex()) {
                            it.write(b.toInt())
//                        emit(offset)
                        }

//                    emit(file.bytes.size)

                        it.write("\r\n".encodeToByteArray())
                        it.flush()
                    }
                    catch(e: Exception) {
                        localRepo.updateStatus(SendStatus.SENT, messageId)
                    }
                }

                localRepo.updateStatus(SendStatus.SENT, messageId)
            }
        }
    }

    fun close() {
        socket = null
        serverSocket = null

        disconnectReceiver.unregister(context)
    }

    private suspend fun listen(socket: BluetoothSocket) {
        withContext(Dispatchers.IO) {
            val id = socket.remoteDevice.address

            try {
                while(socket.isConnected) {
                    val body = mutableListOf<Char>()
                    var last: Char = 'l'

                    var abort = false

                    while(true) {
                        val value = socket.inputStream.read()

                        logcat {
                            "Stream value: ${value.toChar()}, Body: ${body.joinToString(separator = "")}, Last: $last"
                        }

                        if(value == -1) {
                            abort = true
                            break
                        }

                        body.add(value.toChar())

                        if(body.takeLast(2).joinToString(separator = "") == "\r\n") break
                    }

                    logcat { "Outside this thing" }

                    if(abort) break

                    val message = Message(
                        _id = "",
                        who = id,
                        is_by_me = false,
                        body = body.joinToString(separator = ""),
                        type = MessageType.BT,
                        status = SendStatus.SENT,
                        created_at = 0,
                        updated_at = 0
                    )

                    localRepo.saveMessage(message, false)
                }
            }
            catch(e: Exception) {
                logcat { e.stackTraceToString() }
            }
        }
    }

    private suspend fun bond(context: Context, device: BluetoothDevice): Boolean {
        val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)!!
        val adapter = manager.adapter!!

        return if(device.bondState != BluetoothDevice.BOND_BONDED) {
            suspendCoroutine {
                val bondReceiver = object: BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        logcat { "Intent received with extras: ${intent?.extras?.toString()}" }
                        if(intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)

                            if(state != BluetoothDevice.BOND_BONDING) {
                                context?.unregisterReceiver(this)
                                it.resume(state == BluetoothDevice.BOND_BONDED)
                            }
                        }
                    }
                }

                bondReceiver.register(context, BluetoothDevice.ACTION_BOND_STATE_CHANGED, isExported = true)

                if(device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
            }
        }
        else true
    }
}

fun BroadcastReceiver.register(context: Context, action: String, isExported: Boolean = false) {
    val export = if(isExported) ContextCompat.RECEIVER_EXPORTED else ContextCompat.RECEIVER_NOT_EXPORTED

    ContextCompat.registerReceiver(context, this, IntentFilter(action), export)
}

fun BroadcastReceiver.unregister(context: Context, silent: Boolean = true) {
    if(silent) {
        try {
            context.unregisterReceiver(this)
        }
        catch(e: Exception) {
            e.printStackTrace()
        }
    }
    else {
        context.unregisterReceiver(this)
    }
}

val isBelowS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

enum class BluetoothConnect {
    ON, OFF, UNSUPPORTED, NO_PERMISSION
}

fun Context.isBluetoothOn(): BluetoothConnect {
    val hasPermission = isBelowS || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    val bluetoothManager = ContextCompat.getSystemService(this, BluetoothManager::class.java)!!

    val adapter = bluetoothManager.adapter

    return if (hasPermission) {
        if(adapter == null) BluetoothConnect.UNSUPPORTED
        if (adapter.isEnabled) BluetoothConnect.ON
        else BluetoothConnect.OFF
    }
    else BluetoothConnect.NO_PERMISSION
}

fun Context.hasPermission(vararg permission: String): Boolean {
    val isGranted = permission
        .map {
            if(it.isEmpty()) true
            else ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        .all { it }

    return isGranted
}
