package com.exzell.simpooassessment.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.exzell.simpooassessment.local.LocalRepository
import com.exzell.simpooassessment.local.Message
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.local.model.SendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import logcat.logcat
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object BluetoothConnector {
    val ERROR_SEARCH_COMPLETE = "search complete"

    @SuppressLint("MissingPermission")
    fun searchForDevices(context: Context): Flow<List<BluetoothDevice>> = callbackFlow {
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

    private val OBEX_UUID = UUID.fromString("d51e2d9f-4846-4963-b3cc-ae6d7b0bf8b5")

    suspend fun connect(context: Context, deviceID: String): BluetoothSocket? {
        val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)!!
        val adapter = manager.adapter!!

        val device = adapter.getRemoteDevice(deviceID)

        return connect(context, device)
    }

    suspend fun createSocket(context: Context): Pair<String, BluetoothSocket> {
        return withContext(Dispatchers.IO) {
            val deviceResult = async {
                suspendCoroutine<BluetoothDevice> { cont ->
                    val connectedReceiver = object: BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            val device = IntentCompat.getParcelableExtra(intent!!, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

                            logcat { "Bluetooth device connected: ${device}" }
                            this.unregister(context!!)
                            cont.resume(device!!)
                        }
                    }

                    connectedReceiver.register(context, BluetoothDevice.ACTION_ACL_CONNECTED, isExported = true)
                }
            }

            val socketResult = async {
                val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)!!
                val adapter = manager.adapter!!

                val server = adapter.listenUsingInsecureRfcommWithServiceRecord("Simpoo", OBEX_UUID)

                server.accept()/*.also { server.close() }*/
            }

            deviceResult.await().address to socketResult.await()
        }
    }

    suspend fun connect(context: Context, device: BluetoothDevice): BluetoothSocket? {
        val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)!!
        val adapter = manager.adapter!!

        return withContext(Dispatchers.IO) {
            if(bond(context, device)) {
                adapter.cancelDiscovery()

                val uuids = listOf(OBEX_UUID)//getUUids()

                var bSocket: BluetoothSocket? = null

                logcat { "UUIDS\n${uuids.joinToString(separator = "\n")}" }

                for(uuid in uuids) {
                    val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)

                    try {
                        socket.connect()
                        bSocket = socket
                        break
                    }
                    catch(e: Exception) {
                        logcat { e.stackTraceToString() }
                        continue
                    }
                }

                bSocket
            }
            else null
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun bond(context: Context, device: BluetoothDevice): Boolean {
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

    data class BtFile(
        val name: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    suspend fun listen(id: String, socket: BluetoothSocket, localRepo: LocalRepository) {
        withContext(Dispatchers.IO) {
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

    suspend fun sendBytes(socket: BluetoothSocket, file: BtFile, close: Boolean = false): Flow<Int> {
        return flow {
            logcat { "inside sendBytes thing and socket state is ${socket.isConnected}" }

            socket.outputStream.also {
                for((offset, b) in file.bytes.withIndex()) {
                    it.write(b.toInt())
                    emit(offset)
                }

                emit(file.bytes.size)

                it.write("\r\n".encodeToByteArray())
                it.flush()
            }

//            if(close) {
//                try {
//                    socket.close()
//                } catch (e: Exception) {
//                    logcat { e.stackTraceToString() }
//                }
//            }
        }.flowOn(Dispatchers.IO)
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
