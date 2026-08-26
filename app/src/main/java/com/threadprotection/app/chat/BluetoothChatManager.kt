package com.threadprotection.app.chat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import com.threadprotection.app.crypto.PqcChatCrypto
import com.threadprotection.app.data.SettingsRepository
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
 * v1 scope, stated plainly: one active conversation at a time, and message text lives only in
 * memory for the current connection (nothing is written to disk). What *is* persisted, in
 * SettingsRepository.chatHistoryFlow, is just the lightweight contact list — address, name, last
 * chatted time — so a device you've talked to before shows up under "History" without needing to
 * be rediscovered. The listening server socket only runs while the Chat screen is open, not as a
 * background service.
 */
class BluetoothChatManager(private val context: Context, private val settingsRepository: SettingsRepository) {

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

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    /** The peer's long-term mesh identity, learned during the handshake below — null if they're
     *  on a version without mesh support, or the exchange simply failed (best-effort, doesn't
     *  block the live chat session itself). Used to record them into Chat History so they're
     *  reachable later via MeshRelayManager even without a live connection. */
    private val _connectedPeerNodeId = MutableStateFlow<String?>(null)
    val connectedPeerNodeId: StateFlow<String?> = _connectedPeerNodeId.asStateFlow()

    private val _connectedPeerPublicKeyB64 = MutableStateFlow<String?>(null)
    val connectedPeerPublicKeyB64: StateFlow<String?> = _connectedPeerPublicKeyB64.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var discoveryReceiver: BroadcastReceiver? = null

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
                        val rssiRaw = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        val info = BtDeviceInfo(
                            address = device.address,
                            name = device.deviceName(),
                            bonded = false,
                            rssi = if (rssiRaw == Short.MIN_VALUE) null else rssiRaw.toInt(),
                            kind = device.classify(),
                        )
                        // Update in place (not just append-if-new) so signal strength keeps refreshing
                        // live as the same device is re-reported during a scan.
                        _discovered.value = if (_discovered.value.any { it.address == info.address }) {
                            _discovered.value.map { if (it.address == info.address) info else it }
                        } else {
                            _discovered.value + info
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
        val out = DataOutputStream(socket.outputStream)
        val input = DataInputStream(socket.inputStream)

        val ok = runCatching {
            writeFrame(out, MAGIC)
            val peerMagic = readFrame(input) ?: error("no magic")
            if (!peerMagic.contentEquals(MAGIC)) error("peer isn't Thread Protection")

            if (isInitiator) {
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
        }.getOrNull()

        if (ok == null) {
            runCatching { socket.close() }
            _connState.value = BtChatConnState.FAILED
            return
        }

        // Best-effort: also swap long-term mesh identities over the channel this session key just
        // secured, so this contact becomes reachable later via MeshRelayManager even without a
        // live connection. Purely additive — if it fails, the live chat session still works fine.
        val peerIdentity = runCatching { exchangeMeshIdentity(out, input, ok, isInitiator) }.getOrNull()

        activeSocket = socket
        sessionKey = ok
        _connectedDeviceAddress.value = runCatching { socket.remoteDevice.address }.getOrNull()
        _connectedDeviceName.value = runCatching { socket.remoteDevice.deviceName() }.getOrNull()
        _connectedPeerNodeId.value = peerIdentity?.nodeId
        _connectedPeerPublicKeyB64.value = peerIdentity?.let { Base64.encodeToString(it.publicKeyBytes, Base64.NO_WRAP) }
        _connState.value = BtChatConnState.CONNECTED
        readLoop(socket)
    }

    private suspend fun exchangeMeshIdentity(
        out: DataOutputStream,
        input: DataInputStream,
        key: PqcChatCrypto.SessionKey,
        isInitiator: Boolean,
    ): MeshIdentityInfo? {
        val myIdentity = MeshIdentityStore.ensure(settingsRepository)
        val myFrame = PqcChatCrypto.encrypt(key, MeshIdentityInfo(myIdentity.nodeId, myIdentity.publicKeyBytes).encode())
        return if (isInitiator) {
            writeFrame(out, myFrame)
            readFrame(input)?.let { PqcChatCrypto.decrypt(key, it) }?.let { MeshIdentityInfo.decode(it) }
        } else {
            val peerFrame = readFrame(input)
            writeFrame(out, myFrame)
            peerFrame?.let { PqcChatCrypto.decrypt(key, it) }?.let { MeshIdentityInfo.decode(it) }
        }
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
        _connectedDeviceAddress.value = null
        _connectedPeerNodeId.value = null
        _connectedPeerPublicKeyB64.value = null
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
        _connectedDeviceAddress.value = null
        _connectedPeerNodeId.value = null
        _connectedPeerPublicKeyB64.value = null
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

    /** Real Bluetooth Class of Device, broadcast by the peer itself as part of standard discovery
     *  — not inferred from its name. */
    private fun BluetoothDevice.classify(): BtDeviceKind =
        when (runCatching { bluetoothClass?.majorDeviceClass }.getOrNull()) {
            BluetoothClass.Device.Major.PHONE -> BtDeviceKind.PHONE
            BluetoothClass.Device.Major.COMPUTER -> BtDeviceKind.COMPUTER
            BluetoothClass.Device.Major.AUDIO_VIDEO -> BtDeviceKind.AUDIO
            BluetoothClass.Device.Major.WEARABLE -> BtDeviceKind.WEARABLE
            else -> BtDeviceKind.GENERIC
        }

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
