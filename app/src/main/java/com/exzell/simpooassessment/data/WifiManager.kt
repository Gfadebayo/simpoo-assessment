@file:OptIn(ExperimentalStdlibApi::class)

package com.exzell.simpooassessment.data

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.exzell.simpooassessment.local.LocalRepository
import com.exzell.simpooassessment.local.Message
import com.exzell.simpooassessment.local.model.MessageType
import com.exzell.simpooassessment.local.model.SendStatus
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.logcat
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

class WifiManager(
    private val context: Context,
    private val localRepo: LocalRepository,
    private val scope: CoroutineScope
) {
    private companion object {
        const val PORT = 13199
    }

    private val manager by lazy { ContextCompat.getSystemService(context, WifiP2pManager::class.java)!! }

    private val wifi by lazy { ContextCompat.getSystemService(context, WifiManager::class.java)!! }

    private var currentChannel: WifiP2pManager.Channel? = null
        set(value) {
            try {
                logcat { "Attempting to close current channel: ${field}" }
                field?.close()
            }
            catch(e: Exception) {

            }
            field = value
        }

    private var currentGroup: WifiP2pGroup? = null

    private val _clientStateFlow = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val clientFlow = _clientStateFlow.asStateFlow()

    private val _connectionStateFlow = MutableStateFlow<Pair<Status?, String>>(null to "")
    val connectionFlow = _connectionStateFlow.asStateFlow()

    val isTurnedOn: Boolean
        get() = wifi.isWifiEnabled

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } ?: false

    enum class Status { CREATING, WAITING, JOINING, CONNECTED, DISCONNECTED }

    val isSupported: Boolean
        get() {
            val hasConfig = try {
                WifiP2pConfig.Builder()
                    .setNetworkName("DIRECT-test")
                    .setPassphrase("a wonderful world")
                    .build()
                true
            }
            catch (e: Throwable) {
                logcat { e.stackTraceToString() }
                false
            }

            return wifi.isP2pSupported
                    && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
                    && hasConfig
        }

    private var serverSocket: ServerSocket? = null
        set(value) {
            try {
                logcat { "Attempting to close current server socket: $field" }
                field?.close()
            }
            catch (e: Exception) { }

            field = value
            scope.launch(Dispatchers.IO) {
                try {
                    if (value != null) {
                        logcat { "Listening for incoming connections" }
                        socket = value.accept()
                        logcat { "Client socket found: $socket" }
                        _connectionStateFlow.update { it.copy(first = Status.CONNECTED) }
                    }
                }
                catch(e: Exception) {
                    logcat { e.stackTraceToString() }
                }
            }
        }

    private val clientReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            logcat { "Client receiver called with intent ${intent?.extras?.keySet()?.joinToString()}" }

            scope.launch(Dispatchers.IO) {
                val info = intent?.let {
                    IntentCompat.getParcelableExtra(it, WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                } ?: return@launch

                logcat { "P2P info is $info" }

                val group = intent.let {
                    IntentCompat.getParcelableExtra(it, WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                } ?: getGroupInfo() ?: return@launch

                if(group.isGroupOwner && serverSocket == null) {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(PORT))
                    }
                    _connectionStateFlow.update { it.copy(first = Status.WAITING) }
                }
                else if(!group.isGroupOwner && info.groupOwnerAddress != null) {
                    socket = Socket(info.groupOwnerAddress.hostAddress, PORT)
                    _connectionStateFlow.update { it.copy(first = Status.CONNECTED) }
                }

                _clientStateFlow.update { (it + (group?.clientList ?: emptyList())).distinct() }
            }
        }
    }

    fun turnOn(activity: Activity): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // can also use `Settings.Panel.ACTION_WIFI` to enable/disable only WiFi
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
                activity.startActivityForResult(panelIntent, 545)
                true
            } else {
                // use previous solution, add appropriate permissions to AndroidManifest file (see answers above)
                wifi.isWifiEnabled = true
                true
            }
        }
        catch(e: Exception) {
            logcat { e.stackTraceToString() }
            false
        }
    }

    private suspend fun getGroupInfo(): WifiP2pGroup? {
        return suspendCoroutine { cont ->
            manager.requestGroupInfo(currentChannel!!) {
                logcat { "Group found as $it" }
                cont.resume(it)
            }
        }
    }

    init {
        currentChannel = manager.initialize(context, Looper.getMainLooper(), null)

        clientReceiver.register(context, WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION, isExported = true)
    }

    //suspend because it is necessary the operations complete before exiting.
    suspend fun stop() {
        socket = null

        serverSocket = null

        clientReceiver.unregister(context)

//        withTimeout(1000) { removeGroup() }

        currentChannel = null
    }

    suspend fun removeGroup(): Boolean {
        if(currentChannel == null) return true

        logcat { "Sigh" }

        return suspendCoroutine {
            logcat { "Sigh 1" }
            manager.removeGroup(currentChannel, object: WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    logcat { "Sigh 3" }
                    currentGroup = null
                    it.resume(true)
                }

                override fun onFailure(reason: Int) {
                    logcat { "Sigh 2" }
                    logcat(priority = LogPriority.ERROR) { "Remove group fail: $reason" }
                    it.resume(false)
                }
            })
        }
    }

    suspend fun createQrCode(): Bitmap? {
        if(!wifi.isWifiEnabled) return null

        return withContext(Dispatchers.IO) {
            _connectionStateFlow.update { it.copy(first = Status.CREATING) }
            removeGroup()

            val random = Random.nextBytes(10).toHexString()
            val password = UUID.randomUUID().toString().replace("-", "")
            val groupName = "DIRECT-$random"

            val config = WifiP2pConfig.Builder()
                .setNetworkName(groupName)
                .setPassphrase(password)
                .build()

            suspendCoroutine {
                manager.createGroup(currentChannel!!, config, object: WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val content = """
                                    {
                                        "group_id": "$groupName",
                                        "password": "$password"
                                    }
                                """.trimIndent()

                        it.resume(
                            BarcodeEncoder().encodeBitmap(
                                content,
                                BarcodeFormat.QR_CODE,
                                400,
                                400
                            )
                        )
                    }

                    override fun onFailure(reason: Int) {
                        logcat(priority = LogPriority.ERROR) { "Group creation failed with reason $reason" }
                        it.resume(null)
                    }
                })
            }
        }
    }

    suspend fun connectUsingQr(content: String): Boolean {
        if(!wifi.isWifiEnabled) return false

        removeGroup()

        val (groupId, password) = JSONObject(content).let {
            it.getString("group_id") to it.getString("password")
        }

        logcat { "Group id $groupId and Password: $password" }

        val config = WifiP2pConfig.Builder()
            .setNetworkName(groupId)
            .setPassphrase(password)
            .build()

        _connectionStateFlow.update { it.copy(first = Status.JOINING) }

        val connected = suspendCoroutine<Boolean> {
            manager.connect(currentChannel, config, object: WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    it.resume(true)
                }

                override fun onFailure(reason: Int) {
                    logcat(priority = LogPriority.ERROR) { "Connect using QR error: $reason" }
                }
            })
        }

        return true
    }

    fun sendBytes(data: String) {
        scope.launch(Dispatchers.IO) {

            val id = String(socket!!.inetAddress.address)
            logcat { "inside sendBytes thing and socket state is ${socket!!.isConnected}" }

            socket!!.outputStream.also {
                val dataBytes = data.encodeToByteArray()

                for((offset, b) in dataBytes.withIndex()) {
                    it.write(b.toInt())
//                    emit(offset)
                }

//                emit(dataBytes.size)

                it.write("\r\n".encodeToByteArray())
                it.flush()

                val message = Message(
                    _id = "",
                    who = id,
                    is_by_me = true,
                    body = data,
                    type = MessageType.WIFI,
                    status = SendStatus.SENT,
                    created_at = 0,
                    updated_at = 0
                )

                localRepo.saveMessage(message)
            }
        }
    }

    private var socket: Socket? = null
        set(value) {
            try {
                if(field != null) _connectionStateFlow.update { it.copy(first = Status.DISCONNECTED) }
                logcat { "Attempting to close current socket: $field" }
                field?.close()
            }
            catch (e: Exception) { }

            field = value
            if(value != null) {
                scope.launch(Dispatchers.IO) {
                    _connectionStateFlow.update { it.copy(second = String(value.inetAddress.address)) }
                    listen(String(value.inetAddress.address), value, localRepo)
                }
            }
        }

    private suspend fun listen(id: String, socket: Socket, localRepo: LocalRepository) {
        withContext(Dispatchers.IO) {
            logcat { "Now listening on socket $socket with id $id with state: ${socket.isConnected}" }

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
                        type = MessageType.WIFI,
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
}