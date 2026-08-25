package com.threadprotection.app.chat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.threadprotection.app.crypto.PqcChatCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

sealed interface ChatEvent {
    data class MessageReceived(val id: String, val text: String, val atMs: Long) : ChatEvent
    data class MessageDelivered(val id: String) : ChatEvent
    data object PeerTyping : ChatEvent
    data object PeerDisconnected : ChatEvent
}

/**
 * Real Bluetooth Classic (RFCOMM) nearby chat — no internet, no server. README: "whoever has the
 * application installed only those people can chat." Discovery finds any nearby Bluetooth device
 * (that's a phone-OS-level fact Android exposes to every app), but a *connection* only succeeds
 * if the peer speaks this exact handshake: matching magic bytes on an app-specific service UUID,
 * followed by a real ML-KEM-768 key exchange (see PqcChatCrypto). Anything else — a random
 * Bluetooth headset, someone else's phone without Thread Protection — never gets past that
 * handshake, so it never becomes a usable connection.
 *
 * v1 scope, stated plainly: one active conversation at a time, and chat history lives only in
 * memory for the current connection (nothing is written to disk). The listening server socket
 * only runs while the Chat screen is open, not as a background service.
 */
class BluetoothChatManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var activeSocket: BluetoothSocket? = null
    private var sessionKey: PqcChatCrypto.SessionKey? = null
    private val writeMutex = Mutex()

    private val _connState = MutableStateFlow(BtChatConnState.IDLE)
    val connState: StateFlow<BtChatConnState> = _connState.asStateFlow()

    private val _discovered = MutableStateFlow<List<BtDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BtDeviceInfo>> = _discovered.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var discoveryReceiver: BroadcastReceiver? = null

    val bondedDevices: List<BtDeviceInfo>
        get() = runCatching { adapter?.bondedDevices }.getOrNull()
            ?.map { BtDeviceInfo(it.address, it.deviceName(), bonded = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Starts the listening server socket (accepts inbound connections) — call once when entering Chat. */
    fun startListening() {
        val a = adapter ?: run { _connState.value = BtChatConnState.BT_UNAVAILABLE; return }
        if (!a.isEnabled) { _connState.value = BtChatConnState.BT_UNAVAILABLE; return }
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            // "Insecure" here means classic Bluetooth's own pairing/encryption is skipped, not that
            // the connection is unencrypted — it means devices don't need to complete OS-level PIN
            // pairing before talking, since our own ML-KEM + AES-256-GCM handshake (below) is the
            // actual security layer, and it's the same regardless of which RFCOMM variant carries it.
            val serverSocket = runCatching {
                a.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
            }.getOrElse { _connState.value = BtChatConnState.NO_PERMISSION; return@launch }
            acceptLoop(serverSocket)
        }
    }

    private suspend fun acceptLoop(serverSocket: BluetoothServerSocket) {
        while (true) {
            val socket = runCatching { serverSocket.accept() }.getOrNull() ?: continue
            if (activeSocket != null) {
                runCatching { socket.close() }
                continue
            }
            establishSession(socket, isInitiator = false)
        }
    }

    fun startDiscovery() {
        val a = adapter ?: run { _connState.value = BtChatConnState.BT_UNAVAILABLE; return }
        if (!a.isEnabled) { _connState.value = BtChatConnState.BT_UNAVAILABLE; return }
        registerDiscoveryReceiver()
        _discovered.value = emptyList()
        val started = runCatching { a.startDiscovery() }.getOrDefault(false)
        _connState.value = if (started) BtChatConnState.DISCOVERING else BtChatConnState.NO_PERMISSION
    }

    fun stopDiscovery() {
        runCatching { adapter?.cancelDiscovery() }
    }

    private fun registerDiscoveryReceiver() {
        if (discoveryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        val info = BtDeviceInfo(device.address, device.deviceName(), bonded = false)
                        if (_discovered.value.none { it.address == info.address }) {
                            _discovered.value = _discovered.value + info
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (_connState.value == BtChatConnState.DISCOVERING) _connState.value = BtChatConnState.IDLE
                    }
                }
            }
        }
        discoveryReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)
    }

    fun connectTo(address: String) {
        val a = adapter ?: return
        stopDiscovery()
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return
        _connState.value = BtChatConnState.CONNECTING
        scope.launch {
            val socket = runCatching {
                device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
            }.getOrNull()
            if (socket == null) { _connState.value = BtChatConnState.FAILED; return@launch }
            val connected = runCatching { socket.connect(); true }.getOrDefault(false)
            if (!connected) { _connState.value = BtChatConnState.FAILED; return@launch }
            establishSession(socket, isInitiator = true)
        }
    }

    private suspend fun establishSession(socket: BluetoothSocket, isInitiator: Boolean) {
        _connState.value = BtChatConnState.HANDSHAKING
        val ok = runCatching {
            val out = DataOutputStream(socket.outputStream)
            val input = DataInputStream(socket.inputStream)

            writeFrame(out, MAGIC)
            val peerMagic = readFrame(input) ?: error("no magic")
            if (!peerMagic.contentEquals(MAGIC)) error("peer isn't Thread Protection")

            val key = if (isInitiator) {
                val keyPair = PqcChatCrypto.generateKeyPair()
                writeFrame(out, keyPair.publicKeyBytes)
                val ciphertext = readFrame(input) ?: error("no ciphertext")
                PqcChatCrypto.decapsulate(keyPair.privateKey, ciphertext)
            } else {
                val peerPublicKey = readFrame(input) ?: error("no public key")
                val (ciphertext, sessionKey) = PqcChatCrypto.encapsulate(peerPublicKey)
                writeFrame(out, ciphertext)
                sessionKey
            }
            key
        }.getOrNull()

        if (ok == null) {
            runCatching { socket.close() }
            _connState.value = BtChatConnState.FAILED
            return
        }

        activeSocket = socket
        sessionKey = ok
        _connectedDeviceName.value = runCatching { socket.remoteDevice.deviceName() }.getOrNull()
        _connState.value = BtChatConnState.CONNECTED
        readLoop(socket)
    }

    private suspend fun readLoop(socket: BluetoothSocket) {
        val input = runCatching { DataInputStream(socket.inputStream) }.getOrNull()
        if (input == null) { onSessionEnded(); return }
        while (true) {
            val frame = readFrame(input) ?: break
            val key = sessionKey ?: break
            val plaintext = runCatching { PqcChatCrypto.decrypt(key, frame) }.getOrNull() ?: continue
            when (val wire = ChatWireMessage.decode(plaintext)) {
                is ChatWireMessage.Text -> {
                    _events.emit(ChatEvent.MessageReceived(wire.id, wire.body, System.currentTimeMillis()))
                    sendWire(ChatWireMessage.Ack(wire.id))
                }
                is ChatWireMessage.Ack -> _events.emit(ChatEvent.MessageDelivered(wire.id))
                ChatWireMessage.Typing -> _events.emit(ChatEvent.PeerTyping)
                null -> Unit
            }
        }
        onSessionEnded()
    }

    private suspend fun onSessionEnded() {
        _events.emit(ChatEvent.PeerDisconnected)
        activeSocket = null
        sessionKey = null
        _connectedDeviceName.value = null
        _connState.value = BtChatConnState.IDLE
    }

    /** Sends a text message and returns its id (used to track delivery via the peer's Ack). */
    fun sendText(text: String): String {
        val id = UUID.randomUUID().toString()
        sendWire(ChatWireMessage.Text(id, text))
        return id
    }

    fun sendTyping() {
        sendWire(ChatWireMessage.Typing)
    }

    private fun sendWire(message: ChatWireMessage) {
        val socket = activeSocket ?: return
        val key = sessionKey ?: return
        scope.launch {
            writeMutex.withLock {
                runCatching {
                    val payload = PqcChatCrypto.encrypt(key, ChatWireMessage.encode(message))
                    writeFrame(DataOutputStream(socket.outputStream), payload)
                }
            }
        }
    }

    fun disconnect() {
        runCatching { activeSocket?.close() }
        activeSocket = null
        sessionKey = null
        _connectedDeviceName.value = null
        _connState.value = BtChatConnState.IDLE
    }

    /** Stops the server socket and discovery — call when leaving Chat entirely. */
    fun shutdown() {
        disconnect()
        serverJob?.cancel()
        serverJob = null
        stopDiscovery()
        discoveryReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        discoveryReceiver = null
    }

    private fun BluetoothDevice.deviceName(): String =
        runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address

    private fun writeFrame(out: DataOutputStream, data: ByteArray) {
        out.writeInt(data.size)
        out.write(data)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray? = runCatching {
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME_BYTES) return null
        val buf = ByteArray(len)
        input.readFully(buf)
        buf
    }.getOrNull()

    companion object {
        /** App-specific RFCOMM service UUID — a device can only be reached over this if it's also running Thread Protection listening on it. */
        private val APP_UUID: UUID = UUID.fromString("9f9f2f9f-303f-4071-beee-32741e782944")
        private const val SERVICE_NAME = "ThreadProtectionChat"
        private val MAGIC = "TPCHAT1".toByteArray()
        private const val MAX_FRAME_BYTES = 1_048_576
    }
}
