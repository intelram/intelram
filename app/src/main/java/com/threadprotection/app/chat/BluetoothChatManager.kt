package com.threadprotection.app.chat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Base64
import com.threadprotection.app.crypto.PqcChatCrypto
import com.threadprotection.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
 * Real nearby chat — no internet, no server. README: "whoever has the application installed only
 * those people can chat." Two separate Bluetooth technologies do two separate jobs here:
 *
 * - **Discovery is BLE.** While Chat is open, this device advertises a small BLE packet carrying
 *   Thread Protection's own 128-bit service UUID ([PRESENCE_SERVICE_UUID]) plus a short
 *   service-data payload holding the signed-in account's display name — nothing else. "Tap to
 *   scan" then runs a BLE scan with a hardware/OS-level [ScanFilter] on that exact UUID, so the
 *   radio itself discards every advertisement that doesn't carry it. A Bluetooth headset, a
 *   laptop, someone else's phone without this app installed — none of them advertise this UUID,
 *   so none of them ever reach [discoveredDevices]. This is different from (and stronger than)
 *   the old approach of showing every nearby device and only checking identity at connect time:
 *   here, showing up in the list *is* the proof the peer has the app running.
 * - **The chat connection itself is Classic Bluetooth (RFCOMM)**, unchanged from before: once you
 *   tap a discovered device, `connectTo` opens an RFCOMM socket to that same MAC address and runs
 *   the existing handshake — magic-byte confirmation, then a real ML-KEM-768 key exchange (see
 *   PqcChatCrypto) — before any message can flow. BLE proved "this is a Thread Protection phone";
 *   RFCOMM carries the actual encrypted conversation, exactly as it always has.
 *
 * Honest limits: BLE *scanning* (central role) is essentially universal on Android hardware, but
 * BLE *advertising* (peripheral role) needs `BluetoothAdapter.isMultipleAdvertisementSupported()`
 * — a small minority of older/low-end devices lack it, and simply won't be discoverable by others
 * (they can still discover everyone else). See [canAdvertise]. Both roles need this device's
 * Bluetooth turned on and, on Android 12+, the BLUETOOTH_SCAN/BLUETOOTH_ADVERTISE runtime
 * permissions granted.
 *
 * v1 scope, stated plainly: one active conversation at a time, and message text lives only in
 * memory for the current connection (nothing is written to disk). What *is* persisted, in
 * SettingsRepository.chatHistoryFlow, is just the lightweight contact list — address, name, last
 * chatted time — so a device you've talked to before shows up under "History" without needing to
 * be rediscovered. The listening server socket and BLE advertising only run while the Chat screen
 * is open, not as a background service.
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

    /** null until we've tried; false means this specific phone's Bluetooth hardware can't act as a
     *  BLE peripheral, so it can't broadcast its own presence (it can still scan for others). */
    private val _canAdvertise = MutableStateFlow<Boolean?>(null)
    val canAdvertise: StateFlow<Boolean?> = _canAdvertise.asStateFlow()

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

    private var activeAdvertiseCallback: AdvertiseCallback? = null
    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null
    private var staleSweepJob: Job? = null

    /** Starts the listening server socket (accepts inbound connections) and BLE presence
     *  advertising — call once when entering Chat. */
    fun startListening() {
        val a = adapter ?: run { _connState.value = BtChatConnState.BT_UNAVAILABLE; return }
        if (!a.isEnabled) { _connState.value = BtChatConnState.BT_UNAVAILABLE; return }
        startAdvertising(a)
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

    /** Broadcasts a small BLE packet — just [PRESENCE_SERVICE_UUID] plus this account's display
     *  name as service data — so other Thread Protection phones can find *this* one by "Tap to
     *  scan" without either device needing to be classic-Bluetooth "discoverable" (which would
     *  need a one-off system dialog per session). Best-effort: if this hardware can't advertise,
     *  [canAdvertise] flips to false and the rest of the app keeps working normally. */
    private fun startAdvertising(a: BluetoothAdapter) {
        val advertiser = a.bluetoothLeAdvertiser
        if (advertiser == null || !a.isMultipleAdvertisementSupported) {
            _canAdvertise.value = false
            return
        }
        if (activeAdvertiseCallback != null) return
        scope.launch {
            val displayName = runCatching { settingsRepository.accountFlow.first()?.name }.getOrNull()
                ?.trim()?.takeIf { it.isNotBlank() } ?: "Thread Protection user"
            val nameBytes = truncateUtf8(displayName, MAX_ADVERTISED_NAME_BYTES)

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(PRESENCE_SERVICE_UUID))
                .build()
            val scanResponse = AdvertiseData.Builder()
                .addServiceData(ParcelUuid(PRESENCE_SERVICE_UUID), nameBytes)
                .build()
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    _canAdvertise.value = true
                }
                override fun onStartFailure(errorCode: Int) {
                    _canAdvertise.value = false
                    activeAdvertiseCallback = null
                }
            }
            activeAdvertiseCallback = callback
            runCatching { advertiser.startAdvertising(settings, data, scanResponse, callback) }
                .onFailure { _canAdvertise.value = false; activeAdvertiseCallback = null }
        }
    }

    private fun stopAdvertising() {
        val callback = activeAdvertiseCallback ?: return
        activeAdvertiseCallback = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
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

    /** Runs a time-boxed BLE scan filtered to [PRESENCE_SERVICE_UUID] — see the class doc for why
     *  this, not classic discovery, is what "Tap to scan" runs. The filter is enforced by the
     *  Bluetooth radio/OS itself: [onScanResult] is only ever called for advertisements that
     *  actually carry our UUID, so nothing else (headphones, a laptop, a phone without this app)
     *  can appear in [discoveredDevices]. */
    fun startDiscovery() {
        val a = adapter ?: run { _connState.value = BtChatConnState.BT_UNAVAILABLE; return }
        if (!a.isEnabled) { _connState.value = BtChatConnState.BT_UNAVAILABLE; return }
        val scanner = a.bluetoothLeScanner ?: run { _connState.value = BtChatConnState.BLE_UNSUPPORTED; return }
        stopDiscovery()
        _discovered.value = emptyList()

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(PRESENCE_SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = recordSighting(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::recordSighting)
            override fun onScanFailed(errorCode: Int) {
                _connState.value = BtChatConnState.SCAN_FAILED
                activeScanCallback = null
            }
        }
        activeScanCallback = callback
        val started = runCatching { scanner.startScan(filters, settings, callback); true }.getOrDefault(false)
        if (!started) {
            activeScanCallback = null
            _connState.value = BtChatConnState.NO_PERMISSION
            return
        }
        _connState.value = BtChatConnState.DISCOVERING

        // Devices don't send an explicit "I'm gone" signal over BLE — we infer it by how long it's
        // been since a device's last advertisement, and drop it from the list once it goes stale.
        staleSweepJob?.cancel()
        staleSweepJob = scope.launch {
            while (true) {
                delay(STALE_SWEEP_INTERVAL_MS)
                val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
                _discovered.update { list -> list.filter { it.lastSeenMs >= cutoff } }
            }
        }
        // Auto-stop after a bounded window — continuous BLE_SCAN_MODE_LOW_LATENCY scanning is one
        // of the more battery-hungry radio states, so "Tap to scan" shouldn't run forever.
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_WINDOW_MS)
            if (_connState.value == BtChatConnState.DISCOVERING) stopDiscovery()
        }
    }

    private fun recordSighting(result: ScanResult) {
        val advertisedName = runCatching { result.scanRecord?.getServiceData(ParcelUuid(PRESENCE_SERVICE_UUID)) }
            .getOrNull()
            ?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val info = BtDeviceInfo(
            address = result.device.address,
            name = advertisedName ?: "Thread Protection user",
            bonded = false,
            rssi = result.rssi,
            kind = BtDeviceKind.PHONE,
            lastSeenMs = System.currentTimeMillis(),
        )
        // Update in place (not just append-if-new) so signal strength — and a name that arrives a
        // beat later in the scan-response packet — keeps refreshing live for the same device.
        _discovered.update { list ->
            if (list.any { it.address == info.address }) list.map { if (it.address == info.address) info else it }
            else list + info
        }
    }

    fun stopDiscovery() {
        scanTimeoutJob?.cancel(); scanTimeoutJob = null
        staleSweepJob?.cancel(); staleSweepJob = null
        val callback = activeScanCallback
        activeScanCallback = null
        if (callback != null) runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        if (_connState.value == BtChatConnState.DISCOVERING) _connState.value = BtChatConnState.IDLE
    }

    fun connectTo(address: String) {
        val a = adapter ?: return
        // The BLE scan already learned this peer's real app display name — carry it forward
        // instead of falling back to the phone's generic Bluetooth adapter name.
        val advertisedName = _discovered.value.firstOrNull { it.address == address }?.name
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
            establishSession(socket, isInitiator = true, fallbackName = advertisedName)
        }
    }

    private suspend fun establishSession(socket: BluetoothSocket, isInitiator: Boolean, fallbackName: String? = null) {
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
        _connectedDeviceName.value = fallbackName ?: runCatching { socket.remoteDevice.deviceName() }.getOrNull()
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

    /** Stops the server socket, BLE advertising and scanning — call when leaving Chat entirely. */
    fun shutdown() {
        disconnect()
        serverJob?.cancel()
        serverJob = null
        stopDiscovery()
        stopAdvertising()
    }

    private fun BluetoothDevice.deviceName(): String =
        runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address

    /** Truncates to at most [maxBytes] UTF-8 bytes without splitting a multi-byte character in
     *  half — BLE advertisement packets are only a few dozen bytes total, so a display name has to
     *  fit in a handful of them alongside the service UUID. */
    private fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end)
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

        /** BLE presence/discovery UUID — distinct from [APP_UUID] above (that one is the classic
         *  RFCOMM channel the actual chat runs over once connected). This one only ever appears in
         *  a BLE advertisement, and only Thread Protection advertises it, which is what makes
         *  "Tap to scan" show app instances instead of every nearby Bluetooth device. */
        private val PRESENCE_SERVICE_UUID: UUID = UUID.fromString("9f9f2f9f-303f-4071-beee-32741e782946")
        private const val MAX_ADVERTISED_NAME_BYTES = 13
        private const val SCAN_WINDOW_MS = 20_000L
        private const val STALE_AFTER_MS = 8_000L
        private const val STALE_SWEEP_INTERVAL_MS = 3_000L
    }
}
