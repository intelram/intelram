package com.threadprotection.app.chat

import android.Manifest
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import com.threadprotection.app.crypto.PqcChatCrypto
import com.threadprotection.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ChatEvent {
    data class MessageReceived(val id: String, val text: String, val atMs: Long) : ChatEvent
    data class MessageDelivered(val id: String) : ChatEvent
    data object PeerTyping : ChatEvent
    data object PeerDisconnected : ChatEvent

    /** The peer wants to chat and is waiting on this user's Accept/Deny. */
    data class ChatRequested(val requestId: String, val displayName: String) : ChatEvent

    /** The peer accepted our request — only now may either side start chatting. */
    data object ChatAccepted : ChatEvent

    /** The peer declined our request. */
    data object ChatDenied : ChatEvent
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
class BluetoothChatManager private constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverJob: Job? = null
    @Volatile private var activeSocket: BluetoothSocket? = null
    @Volatile private var sessionKey: PqcChatCrypto.SessionKey? = null
    private val writeMutex = Mutex()

    private val _connState = MutableStateFlow(BtChatConnState.IDLE)
    val connState: StateFlow<BtChatConnState> = _connState.asStateFlow()

    /**
     * Whether the BLE radio is scanning right now, published *separately* from [connState].
     *
     * Root cause this fixes — the reported "sender says Connected, recipient says Connection
     * Failed". Scanning and connecting used to share this one [connState] flow, so a scan-lifecycle
     * event could overwrite a perfectly healthy connection state and vice versa. The recipient's
     * Chat screen keeps a continuous scan running; when an inbound RFCOMM link came in, the BLE
     * stack routinely reports `onScanFailed` for the scan it can no longer service, which wrote
     * `SCAN_FAILED` straight over `REQUEST_RECEIVED`/`CONNECTED`. One phone showed a live chat, the
     * other showed a failure banner for a connection that was actually fine. Scan lifecycle now
     * lives here and in [scanFailed]; only real connection transitions may touch [connState].
     */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Set when the BLE scan itself couldn't start or was dropped by the stack. Independent of
     *  [connState] so a scan problem never masquerades as a connection problem. */
    private val _scanFailed = MutableStateFlow(false)
    val scanFailed: StateFlow<Boolean> = _scanFailed.asStateFlow()

    /** True while a connection attempt, pending request or live chat owns the radio. Discovery may
     *  not publish state while this holds — its updates would be describing a different thing.
     *  The rule itself lives in [ChatStateRules] so it can be unit tested. */
    private fun connectionInProgress(): Boolean = ChatStateRules.ownsRadio(_connState.value)

    /** Publishes a state that came from the *discovery* path, dropping it if a connection is live.
     *  This is the single guard that keeps the two sides of a chat consistent. */
    private fun publishScanState(state: BtChatConnState) {
        if (!ChatStateRules.mayPublishScanState(_connState.value)) {
            Log.d(TAG, "publishScanState: ignoring $state — a connection (${_connState.value}) owns the state")
            return
        }
        _connState.value = state
    }

    private val _discovered = MutableStateFlow<List<BtDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BtDeviceInfo>> = _discovered.asStateFlow()

    /** null until we've tried; false means this device isn't broadcasting its presence — check
     *  [advertisePermissionMissing] first, since a denied runtime permission and genuinely
     *  unsupported BLE-peripheral hardware both land here but need very different user-facing
     *  messages and recovery paths. */
    private val _canAdvertise = MutableStateFlow<Boolean?>(null)
    val canAdvertise: StateFlow<Boolean?> = _canAdvertise.asStateFlow()

    /** True specifically when advertising failed because BLUETOOTH_ADVERTISE isn't granted (fixable
     *  by the user) — as opposed to [canAdvertise] being false because the hardware itself can't do
     *  BLE peripheral mode (not fixable). Root-cause fix: earlier this app only tracked canAdvertise,
     *  so a denied runtime permission was shown to the user as a hardware limitation, with no way to
     *  recover short of reinstalling. */
    private val _advertisePermissionMissing = MutableStateFlow(false)
    val advertisePermissionMissing: StateFlow<Boolean> = _advertisePermissionMissing.asStateFlow()

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

    /** Short verification code for the live session — both phones show the same value only if no
     *  one is relaying between them. The KEM exchange is unauthenticated (no server, no PKI), so
     *  reading this aloud is the honest way for two people to rule out a machine-in-the-middle. */
    private val _sessionSafetyCode = MutableStateFlow<String?>(null)
    val sessionSafetyCode: StateFlow<String?> = _sessionSafetyCode.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // Volatile: these are written from the adapter-state BroadcastReceiver (main thread), from the
    // ViewModel's calls into start/stop (main thread), and from coroutines on Dispatchers.IO. Plain
    // vars would let one thread keep reading a stale callback reference and skip a start or a stop.
    @Volatile private var activeAdvertiseCallback: AdvertiseCallback? = null
    @Volatile private var activeScanCallback: ScanCallback? = null
    @Volatile private var advertiseJob: Job? = null
    @Volatile private var scanTimeoutJob: Job? = null
    @Volatile private var staleSweepJob: Job? = null
    @Volatile private var adapterStateReceiver: BroadcastReceiver? = null

    // ── chat-request handshake (application level, on top of the encrypted channel) ──
    /** Id of the request we sent and are waiting on; null once answered or timed out. */
    @Volatile private var outgoingRequestId: String? = null

    /** Id of the request the peer sent us, pending this user's Accept/Deny. */
    @Volatile private var incomingRequestId: String? = null

    @Volatile private var requestTimeoutJob: Job? = null

    /**
     * Exactly one connection attempt or live session at a time, reserved *before* any I/O.
     *
     * Edge case this closes: the accept loop only checked `activeSocket != null`, but activeSocket
     * is assigned at the *end* of the handshake. Two phones requesting at the same moment (or a
     * double-tap on Connect) both got past that check and ran overlapping handshakes on one radio,
     * which is another way the two sides ended up disagreeing about whether they were connected.
     */
    private val connectionSlot = AtomicBoolean(false)

    /** The address of the last connect the user asked for, so [retryLastConnect] has something
     *  real to dial — never a remembered "connection" that isn't there. */
    @Volatile private var lastConnectAddress: String? = null

    /** Why the last connection attempt failed, in the user's words. Cleared when a new attempt
     *  starts. Null when nothing has failed. */
    private val _lastFailureReason = MutableStateFlow<String?>(null)
    val lastFailureReason: StateFlow<String?> = _lastFailureReason.asStateFlow()

    /** Peer details captured at handshake time but not published as "connected" until accepted. */
    private val _pendingPeerName = MutableStateFlow<String?>(null)
    private val _pendingPeerAddress = MutableStateFlow<String?>(null)

    @Volatile private var _pendingPeerIdentity: MeshIdentityInfo? = null

    /** Guards the start/stop of BLE advertising against a race: building the advertisement payload
     *  needs a suspend read of the account name, so a `stopAdvertising()` call that lands while that
     *  read is in flight must not be silently ignored just because `activeAdvertiseCallback` hasn't
     *  been assigned yet (root-cause bug: it previously was, leaking an advertisement that nothing
     *  could stop until Chat was reopened and closed a second time). */
    private val advertiseLock = Mutex()

    /** Watches for the user toggling Bluetooth while Chat is open. Without this, turning Bluetooth
     *  off left `activeAdvertiseCallback` set to a callback the stack had already torn down, so
     *  turning Bluetooth back on and tapping scan hit the "already advertising, skipping" path and
     *  this device silently never became discoverable again until Chat was closed and reopened. */
    private fun registerAdapterStateReceiver() {
        if (adapterStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.w(TAG, "adapterState: Bluetooth turned off — clearing scan/advertise state")
                        forgetRadioState()
                        connectionSlot.set(false)
                        _connState.value = BtChatConnState.BT_UNAVAILABLE
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "adapterState: Bluetooth turned back on — restarting listener and advertising")
                        _connState.value = BtChatConnState.IDLE
                        startListening()
                        // Network-interruption edge case: the radio going down and back up is the
                        // one moment the nearby list is guaranteed stale, so rebuild it rather than
                        // leaving the user looking at devices that were visible before the outage.
                        resumeDiscoveryIfWanted()
                    }
                }
            }
        }
        adapterStateReceiver = receiver
        runCatching { context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) }
            .onFailure { Log.w(TAG, "registerAdapterStateReceiver: failed", it); adapterStateReceiver = null }
    }

    /** Drops references to scan/advertise callbacks the Bluetooth stack has already discarded (it
     *  invalidates them when the adapter goes down), so the next start is treated as fresh. */
    private fun forgetRadioState() {
        scanTimeoutJob?.cancel(); scanTimeoutJob = null
        staleSweepJob?.cancel(); staleSweepJob = null
        activeScanCallback = null
        advertiseJob?.cancel(); advertiseJob = null
        activeAdvertiseCallback = null
        serverJob?.cancel(); serverJob = null
        _discovered.value = emptyList()
        _canAdvertise.value = null
        _isScanning.value = false
    }

    /** Starts the listening server socket (accepts inbound connections) and BLE presence
     *  advertising — call once when entering Chat. */
    fun startListening() {
        registerAdapterStateReceiver()
        val a = adapter ?: run {
            Log.w(TAG, "startListening: no BluetoothAdapter on this device")
            _connState.value = BtChatConnState.BT_UNAVAILABLE
            return
        }
        if (!a.isEnabled) {
            Log.w(TAG, "startListening: Bluetooth is disabled")
            forgetRadioState()
            _connState.value = BtChatConnState.BT_UNAVAILABLE
            return
        }
        Log.d(TAG, "startListening: adapter ready, starting BLE advertising + RFCOMM listener")
        // Stand the mesh relay's classic-Bluetooth inquiry down while Chat owns the radio — see
        // MeshRelayManager.foregroundBleActive.
        MeshRelayManager.foregroundBleActive = true
        startAdvertising(a)
        if (serverJob?.isActive == true) {
            Log.d(TAG, "startListening: RFCOMM server already running, skipping")
            return
        }
        serverJob = scope.launch {
            // "Insecure" here means classic Bluetooth's own pairing/encryption is skipped, not that
            // the connection is unencrypted — it means devices don't need to complete OS-level PIN
            // pairing before talking, since our own ML-KEM + AES-256-GCM handshake (below) is the
            // actual security layer, and it's the same regardless of which RFCOMM variant carries it.
            val serverSocket = runCatching {
                a.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
            }.getOrElse {
                Log.e(TAG, "startListening: listenUsingInsecureRfcommWithServiceRecord failed", it)
                // Never over a live connection: startListening() is idempotent and gets re-run on
                // every entry to Chat, and it must not relabel an open chat as a permission error.
                publishScanState(BtChatConnState.NO_PERMISSION)
                return@launch
            }
            Log.d(TAG, "startListening: RFCOMM server socket bound on $APP_UUID, accepting")
            acceptLoop(serverSocket)
        }
    }

    private fun hasAdvertisePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Broadcasts a small BLE packet — just [PRESENCE_SERVICE_UUID] plus this account's display
     *  name as service data — so other Thread Protection phones can find *this* one by "Tap to
     *  scan" without either device needing to be classic-Bluetooth "discoverable" (which would
     *  need a one-off system dialog per session). Best-effort: if this hardware can't advertise,
     *  or BLUETOOTH_ADVERTISE isn't granted, [canAdvertise] flips to false (with
     *  [advertisePermissionMissing] distinguishing which one) and the rest of the app keeps
     *  working normally. */
    private fun startAdvertising(a: BluetoothAdapter) {
        val advertiser = a.bluetoothLeAdvertiser
        if (advertiser == null || !a.isMultipleAdvertisementSupported) {
            Log.w(TAG, "startAdvertising: BLE peripheral mode not supported on this hardware (advertiser=$advertiser, multiAdvertise=${a.isMultipleAdvertisementSupported})")
            _canAdvertise.value = false
            _advertisePermissionMissing.value = false
            return
        }
        // Root-cause fix: previously this method only ever discovered a missing BLUETOOTH_ADVERTISE
        // grant via the SecurityException startAdvertising() throws — and on some OEM builds that
        // call fails silently instead of throwing, so advertising just never started with no signal
        // as to why. Checking explicitly up front makes the failure deterministic and loggable.
        if (!hasAdvertisePermission()) {
            Log.w(TAG, "startAdvertising: BLUETOOTH_ADVERTISE not granted")
            _canAdvertise.value = false
            _advertisePermissionMissing.value = true
            return
        }
        _advertisePermissionMissing.value = false
        if (advertiseJob?.isActive == true || activeAdvertiseCallback != null) {
            Log.d(TAG, "startAdvertising: already advertising, skipping")
            return
        }
        advertiseJob = scope.launch {
            val displayName = advertisedDisplayName()
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
            // Both packets must stay inside BLE's hard 31-byte legacy advertisement budget or the
            // radio rejects the whole thing with ADVERTISE_FAILED_DATA_TOO_LARGE and this device
            // silently never becomes discoverable. A 128-bit service UUID already costs 18 bytes
            // (2 header + 16 UUID), so the name is capped at MAX_ADVERTISED_NAME_BYTES and the
            // adapter's own device name is explicitly excluded from both — left to its default it
            // can be appended by the stack and blow the budget on phones with a long default name.
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(PRESENCE_SERVICE_UUID), nameBytes)
                .build()
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    Log.i(TAG, "startAdvertising: onStartSuccess, mode=${settingsInEffect.mode}, txPower=${settingsInEffect.txPowerLevel}")
                    _canAdvertise.value = true
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "startAdvertising: onStartFailure errorCode=$errorCode")
                    _canAdvertise.value = false
                    scope.launch { advertiseLock.withLock { activeAdvertiseCallback = null } }
                }
            }
            // Guards against startAdvertising/stopAdvertising racing each other across the suspend
            // point above (see advertiseLock doc) — if stopAdvertising() already ran while we were
            // reading the account name, this coroutine's own Job is cancelled and coroutineContext
            // is no longer active, so bail out instead of starting an advertisement nothing tracks.
            advertiseLock.withLock {
                if (!currentCoroutineContext().isActive) {
                    Log.d(TAG, "startAdvertising: cancelled before the radio call — Chat was closed meanwhile")
                    return@withLock
                }
                activeAdvertiseCallback = callback
                Log.d(TAG, "startAdvertising: calling BluetoothLeAdvertiser.startAdvertising, name=\"$displayName\" (${nameBytes.size}B), uuid=$PRESENCE_SERVICE_UUID")
                runCatching { advertiser.startAdvertising(settings, data, scanResponse, callback) }
                    .onFailure {
                        Log.e(TAG, "startAdvertising: startAdvertising() threw", it)
                        _canAdvertise.value = false
                        activeAdvertiseCallback = null
                    }
            }
        }
    }

    /**
     * The name other phones see for this device.
     *
     * Falls back to the real hardware model (Build.MODEL — "SM-S921B", "Pixel 8") rather than the
     * generic "Thread Protection user" everyone previously appeared as, which made a list of
     * nearby devices impossible to tell apart. A user who has set an account name gets that
     * instead, since it is the name they chose.
     */
    private suspend fun advertisedDisplayName(): String {
        val account = runCatching { settingsRepository.accountFlow.first()?.name }.getOrNull()
            ?.trim()?.takeIf { it.isNotBlank() }
        if (account != null) return account
        val model = Build.MODEL?.trim()?.takeIf { it.isNotBlank() }
        return model ?: "Thread Protection user"
    }

    private fun stopAdvertising() {
        advertiseJob?.cancel()
        advertiseJob = null
        scope.launch {
            advertiseLock.withLock {
                val callback = activeAdvertiseCallback ?: return@withLock
                activeAdvertiseCallback = null
                Log.d(TAG, "stopAdvertising: stopping BLE advertising")
                runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
            }
        }
    }

    private suspend fun acceptLoop(serverSocket: BluetoothServerSocket) {
        while (currentCoroutineContext().isActive) {
            val socket = runCatching { serverSocket.accept() }.getOrElse {
                // Root-cause fix: this used to `continue` straight back into accept(). Once the
                // server socket is closed (Chat left, Bluetooth turned off) accept() fails
                // instantly and forever, so that was an unthrottled busy-loop pinning a thread and
                // burning battery. Bail out instead — shutdown()/restart owns re-establishing it.
                Log.d(TAG, "acceptLoop: accept() failed, ending listener", it)
                runCatching { serverSocket.close() }
                return
            }
            // Reserve the slot up front, not after the handshake — see [connectionSlot]. A second
            // caller arriving mid-handshake is turned away cleanly instead of racing the first.
            if (!connectionSlot.compareAndSet(false, true)) {
                Log.d(TAG, "acceptLoop: rejecting inbound connection — already in a conversation (${_connState.value})")
                runCatching { socket.close() }
                continue
            }
            Log.i(TAG, "acceptLoop: inbound RFCOMM connection accepted, starting handshake")
            establishSession(socket, isInitiator = false)
        }
        runCatching { serverSocket.close() }
    }

    /** Runs a time-boxed BLE scan filtered to [PRESENCE_SERVICE_UUID] — see the class doc for why
     *  this, not classic discovery, is what "Tap to scan" runs. The filter is enforced by the
     *  Bluetooth radio/OS itself: [onScanResult] is only ever called for advertisements that
     *  actually carry our UUID, so nothing else (headphones, a laptop, a phone without this app)
     *  can appear in [discoveredDevices]. */
    /** True while the Chat screen wants the nearby list kept fresh without further taps. */
    @Volatile private var continuousDiscovery = false

    /** Called when the Chat screen opens: scan straight away and keep the list refreshing. */
    fun startContinuousDiscovery() {
        continuousDiscovery = true
        startDiscovery()
    }

    fun startDiscovery() {
        // A live connection outranks a scan: never re-arm the radio underneath a chat that is
        // connecting, waiting on an accept, or already open.
        if (connectionInProgress()) {
            Log.d(TAG, "startDiscovery: skipped — a connection (${_connState.value}) is using the radio")
            return
        }
        val a = adapter ?: run {
            Log.w(TAG, "startDiscovery: no BluetoothAdapter on this device")
            publishScanState(BtChatConnState.BT_UNAVAILABLE)
            return
        }
        if (!a.isEnabled) {
            Log.w(TAG, "startDiscovery: Bluetooth is disabled")
            publishScanState(BtChatConnState.BT_UNAVAILABLE)
            return
        }
        val scanner = a.bluetoothLeScanner ?: run {
            Log.e(TAG, "startDiscovery: getBluetoothLeScanner() returned null — BLE unsupported on this hardware")
            publishScanState(BtChatConnState.BLE_UNSUPPORTED)
            return
        }
        // Root-cause fix: this used to skip straight to scanner.startScan() and infer a missing
        // permission from whether it threw. On some OEM builds startScan() with a missing
        // BLUETOOTH_SCAN/ACCESS_FINE_LOCATION grant doesn't throw at all — it just never reports a
        // result — which looked identical to "no devices nearby" with zero indication why. Checking
        // explicitly first makes that failure mode deterministic and visible in logs.
        if (!hasScanPermission()) {
            Log.w(TAG, "startDiscovery: scan permission not granted (needs BLUETOOTH_SCAN on API 31+, ACCESS_FINE_LOCATION below)")
            publishScanState(BtChatConnState.NO_PERMISSION)
            return
        }
        if (activeScanCallback != null) {
            Log.d(TAG, "startDiscovery: a scan is already running — restarting it")
        }
        // stopScanRadio(), not stopDiscovery(): the latter also clears the *intent* to keep
        // scanning. Root cause this fixes — startContinuousDiscovery() set continuousDiscovery=true
        // and then immediately called startDiscovery(), whose first act was stopDiscovery(), which
        // set it straight back to false. Continuous discovery therefore never actually ran: the
        // nearby list filled in for one 20-second burst after entering Chat and then went quiet,
        // which is exactly the "devices appear late, or not at all" behaviour that was reported.
        stopScanRadio()
        // Deliberately *not* clearing _discovered here. Re-arming the scan every 20s used to empty
        // the nearby list on each cycle, so devices flickered in and out even while sitting still.
        // The stale sweep below is what removes a device that genuinely stopped advertising.

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(PRESENCE_SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = recordSighting(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "startDiscovery: onBatchScanResults, count=${results.size}")
                results.forEach(::recordSighting)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "startDiscovery: onScanFailed errorCode=$errorCode")
                activeScanCallback = null
                _isScanning.value = false
                // A scan failure is reported on its own channel. It must never be shown as, or
                // overwrite, a connection state — see [_isScanning].
                _scanFailed.value = true
                publishScanState(BtChatConnState.SCAN_FAILED)
            }
        }
        activeScanCallback = callback
        Log.d(TAG, "startDiscovery: calling BluetoothLeScanner.startScan, filtering on uuid=$PRESENCE_SERVICE_UUID")
        val started = runCatching { scanner.startScan(filters, settings, callback); true }
            .onFailure { Log.e(TAG, "startDiscovery: startScan() threw", it) }
            .getOrDefault(false)
        if (!started) {
            activeScanCallback = null
            _isScanning.value = false
            publishScanState(BtChatConnState.NO_PERMISSION)
            return
        }
        _isScanning.value = true
        _scanFailed.value = false
        publishScanState(BtChatConnState.DISCOVERING)

        // Devices don't send an explicit "I'm gone" signal over BLE — we infer it by how long it's
        // been since a device's last advertisement, and drop it from the list once it goes stale.
        staleSweepJob?.cancel()
        staleSweepJob = scope.launch {
            while (true) {
                delay(STALE_SWEEP_INTERVAL_MS)
                val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
                _discovered.update { list ->
                    val fresh = NearbyDeviceList.dropStale(list, cutoff)
                    (list - fresh.toSet()).forEach { Log.d(TAG, "staleSweep: dropping ${it.address} (\"${it.name}\") — no advertisement in ${STALE_AFTER_MS}ms, likely out of range") }
                    fresh
                }
            }
        }
        // Auto-stop after a bounded window — continuous BLE_SCAN_MODE_LOW_LATENCY scanning is one
        // of the more battery-hungry radio states, so "Tap to scan" shouldn't run forever.
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_WINDOW_MS)
            if (!_isScanning.value) return@launch
            if (continuousDiscovery) {
                // Re-arm rather than stop: the nearby list is meant to fill in by itself while the
                // user is on the Chat screen, not only for one 20-second burst after a tap. The
                // radio still gets a brief gap between windows, and any connect or leaving Chat
                // clears continuousDiscovery so this cannot run forever.
                Log.d(TAG, "startDiscovery: window elapsed, re-arming continuous scan")
                delay(SCAN_REARM_GAP_MS)
                if (continuousDiscovery && !connectionInProgress()) startDiscovery()
            } else {
                Log.d(TAG, "startDiscovery: scan window elapsed with no connection, auto-stopping")
                stopDiscovery()
            }
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
            name = advertisedName ?: NearbyDeviceList.PLACEHOLDER_NAME,
            bonded = false,
            rssi = result.rssi,
            kind = BtDeviceKind.PHONE,
            lastSeenMs = System.currentTimeMillis(),
        )
        // Update in place (not just append-if-new) so signal strength — and a name that arrives a
        // beat later in the scan-response packet — keeps refreshing live for the same device.
        _discovered.update { list ->
            if (list.none { it.address == info.address }) {
                Log.i(TAG, "recordSighting: NEW peer ${info.address} name=\"${info.name}\" rssi=${info.rssi} (advertised our service UUID, so it is running this app)")
            }
            NearbyDeviceList.merge(list, info)
        }
    }

    /** Stops scanning *and* the intent to keep scanning — the user left Chat or started a connect
     *  they own. Use [stopScanRadio] when the scan should resume by itself afterwards. */
    fun stopDiscovery() {
        continuousDiscovery = false
        stopScanRadio()
    }

    /** Frees the BLE radio but leaves [continuousDiscovery] alone, so discovery resumes on its own
     *  once whatever borrowed the radio (a handshake, a live chat) is finished with it. */
    private fun stopScanRadio() {
        scanTimeoutJob?.cancel(); scanTimeoutJob = null
        staleSweepJob?.cancel(); staleSweepJob = null
        val callback = activeScanCallback
        activeScanCallback = null
        _isScanning.value = false
        if (callback != null) {
            Log.d(TAG, "stopScanRadio: stopping BLE scan (${_discovered.value.size} peers were visible)")
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
                .onFailure { Log.w(TAG, "stopScanRadio: stopScan() threw", it) }
        }
        if (_connState.value == BtChatConnState.DISCOVERING) _connState.value = BtChatConnState.IDLE
    }

    /** Puts the scan back if the Chat screen still wants one — called after a session ends or a
     *  connection attempt fails, so the user isn't left staring at an empty, frozen nearby list. */
    private fun resumeDiscoveryIfWanted() {
        if (!continuousDiscovery) return
        if (connectionInProgress()) return
        Log.d(TAG, "resumeDiscoveryIfWanted: radio free again, restarting nearby scan")
        scope.launch {
            delay(SCAN_REARM_GAP_MS)
            if (continuousDiscovery && !connectionInProgress()) startDiscovery()
        }
    }

    fun connectTo(address: String) {
        val a = adapter ?: run {
            Log.w(TAG, "connectTo: no BluetoothAdapter")
            _connState.value = BtChatConnState.BT_UNAVAILABLE
            return
        }
        if (!hasConnectPermission()) {
            Log.w(TAG, "connectTo: BLUETOOTH_CONNECT not granted")
            _connState.value = BtChatConnState.NO_PERMISSION
            return
        }
        // Edge case: two taps on Connect, or a tap while an inbound request is already being
        // handled. Without this the second attempt tears down the first one's socket halfway
        // through its handshake, and both phones end up in different states.
        if (!connectionSlot.compareAndSet(false, true)) {
            Log.w(TAG, "connectTo: refused — a connection (${_connState.value}) is already in progress")
            return
        }
        lastConnectAddress = address
        _lastFailureReason.value = null
        // The BLE scan already learned this peer's real app display name — carry it forward
        // instead of falling back to the phone's generic Bluetooth adapter name.
        val advertisedName = _discovered.value.firstOrNull { it.address == address }?.name
        // Stop scanning before connecting: the radio can't do both well at once, and a live scan
        // measurably slows down (and sometimes outright fails) RFCOMM connection setup.
        //
        // stopScanRadio(), not stopDiscovery(): the Chat screen still wants a live nearby list, and
        // clearing that intent here is what used to leave the user staring at a frozen, empty list
        // behind a "Couldn't connect" banner with no way back except leaving and re-entering Chat.
        // Leaving the intent set means resumeDiscoveryIfWanted() puts the scan back the moment this
        // attempt finishes, succeed or fail. Leaving Chat (releaseChatRadioIfLeaving) and shutdown()
        // are what clear the intent for real.
        stopScanRadio()
        val device = runCatching { a.getRemoteDevice(address) }.getOrElse {
            Log.e(TAG, "connectTo: getRemoteDevice($address) failed", it)
            failConnection("That device address is no longer valid — scan again.")
            return
        }
        Log.d(TAG, "connectTo: connecting to $address (\"$advertisedName\") over RFCOMM $APP_UUID")
        _connState.value = BtChatConnState.CONNECTING
        scope.launch {
            val socket = runCatching {
                device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
            }.getOrElse {
                Log.e(TAG, "connectTo: createInsecureRfcommSocketToServiceRecord failed", it)
                failConnection("Couldn't open a Bluetooth channel to that device.")
                return@launch
            }
            // BluetoothSocket.connect() blocks with no timeout parameter of its own and can hang for
            // a long time against an unreachable peer; closing the socket from a watchdog is the
            // documented way to interrupt it, and leaves connState free to move on.
            val watchdog = scope.launch {
                delay(CONNECT_TIMEOUT_MS)
                Log.w(TAG, "connectTo: connect timed out after ${CONNECT_TIMEOUT_MS}ms, closing socket")
                runCatching { socket.close() }
            }
            val connected = runCatching { socket.connect(); true }
                .onFailure { Log.e(TAG, "connectTo: socket.connect() failed", it) }
                .getOrDefault(false)
            watchdog.cancel()
            if (!connected) {
                runCatching { socket.close() }
                failConnection("That device didn't answer — it may have moved out of range or left the Chat screen.")
                return@launch
            }
            Log.i(TAG, "connectTo: RFCOMM socket connected to $address, starting handshake")
            establishSession(socket, isInitiator = true, fallbackName = advertisedName)
        }
    }

    private suspend fun establishSession(socket: BluetoothSocket, isInitiator: Boolean, fallbackName: String? = null) {
        // Free the radio for the whole handshake, on *both* sides.
        //
        // Root cause this fixes — the reported "sender is connected, recipient says Connection
        // Failed". connectTo() already stopped scanning before dialling, but the receiving side
        // reached this method straight from acceptLoop() with a SCAN_MODE_LOW_LATENCY BLE scan
        // still running. A single Bluetooth radio cannot service a low-latency scan and a
        // multi-round-trip RFCOMM key exchange at the same time: the recipient's reads timed out or
        // returned short, its handshake failed, and it published FAILED — while the initiator, whose
        // radio was free, completed and showed a live chat. Stopping the scan here (without
        // clearing the intent to scan, so it resumes afterwards) removes the contention entirely.
        stopScanRadio()
        _connState.value = BtChatConnState.HANDSHAKING
        val out = DataOutputStream(socket.outputStream)
        val input = DataInputStream(socket.inputStream)

        // BluetoothSocket's streams have no read timeout of their own, so a peer that connects and
        // then says nothing used to wedge this coroutine — and, on the responder, the whole accept
        // loop — for good. Closing the socket from a watchdog is the documented way to break a
        // blocked read; withTimeoutOrNull then gives us a definite answer either way.
        val handshakeWatchdog = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            Log.w(TAG, "establishSession: handshake exceeded ${HANDSHAKE_TIMEOUT_MS}ms, closing socket")
            runCatching { socket.close() }
        }
        val ok = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS + 2_000L) {
            runCatching {
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
        }
        handshakeWatchdog.cancel()

        if (ok == null) {
            Log.e(TAG, "establishSession: handshake failed (peer isn't this app, the socket dropped mid-exchange, or it timed out)")
            runCatching { socket.close() }
            failConnection(
                if (isInitiator) "The secure handshake didn't complete. Move the phones closer together and try again."
                else "A nearby phone tried to connect but the secure handshake didn't complete."
            )
            return
        }
        Log.i(TAG, "establishSession: ML-KEM-768 handshake complete, session encrypted")

        activeSocket = socket
        sessionKey = ok
        // Name resolution, best first: the name carried in this app's own BLE advertisement (what
        // the other user actually set, or their phone's model), then the classic Bluetooth adapter
        // name, then the address. The responder has no fallbackName — it never scanned this peer —
        // so look the address up in whatever the last scan did see before falling back.
        val peerAddress = runCatching { socket.remoteDevice.address }.getOrNull()
        _pendingPeerName.value = fallbackName
            ?: peerAddress?.let { addr -> _discovered.value.firstOrNull { it.address == addr }?.name }
            ?: runCatching { socket.remoteDevice.deviceName() }.getOrNull()
        _pendingPeerAddress.value = peerAddress
        _pendingPeerIdentity = null

        // Long-term mesh identity now travels as an ordinary encrypted frame *after* the session is
        // up, never as part of the handshake.
        //
        // Root cause this replaces: the old exchangeMeshIdentity() did a blocking readFrame() with
        // no timeout, right after a DataStore write (MeshIdentityStore.ensure). If that write threw
        // or stalled on one device, that side's runCatching swallowed it and moved on to a live
        // session — while the *other* side sat blocked in readFrame forever, never reaching
        // readLoop, never answering anything. One phone showed a working connection, the other was
        // wedged mid-handshake, and every message sent to it went unanswered. Worse, the abandoned
        // read left the two sides out of step in the frame stream, so nothing could recover.
        // Sending it as a normal typed frame removes the blocking read entirely: if it never
        // arrives, the chat is simply not mesh-reachable, which is exactly the "purely additive"
        // behaviour this was always documented to have.
        sendOwnMeshIdentity()

        // The encrypted channel is up, but that is NOT the same as "the other person agreed to
        // chat". The initiator asks; the recipient waits for their user to answer. Only an explicit
        // ChatAccept moves either side to CONNECTED — see readLoop below.
        if (isInitiator) {
            val requestId = UUID.randomUUID().toString()
            outgoingRequestId = requestId
            val myName = advertisedDisplayName()
            Log.i(TAG, "establishSession: sending chat request $requestId as \"$myName\"")
            sendWire(ChatWireMessage.ChatRequest(requestId, myName))
            _connState.value = BtChatConnState.REQUEST_SENT
            startRequestTimeout(requestId)
        } else {
            // Responder: stay pending until the peer's ChatRequest frame arrives in readLoop.
            Log.d(TAG, "establishSession: awaiting peer's chat request")
        }
        readLoop(socket)
    }

    /** Fails a request that nobody ever answered, instead of leaving the UI pending forever. */
    private fun startRequestTimeout(requestId: String) {
        requestTimeoutJob?.cancel()
        requestTimeoutJob = scope.launch {
            delay(REQUEST_TIMEOUT_MS)
            if (outgoingRequestId == requestId && _connState.value == BtChatConnState.REQUEST_SENT) {
                Log.w(TAG, "chat request $requestId timed out after ${REQUEST_TIMEOUT_MS}ms")
                _connState.value = BtChatConnState.REQUEST_TIMEOUT
                _lastFailureReason.value = "They didn't answer in time. You can send another request."
                outgoingRequestId = null
                disconnect(resetState = false)
            }
        }
    }

    /** Called when this user taps Accept on an incoming request. */
    fun acceptChatRequest(): Boolean {
        val id = incomingRequestId
        if (id == null || activeSocket == null || sessionKey == null) {
            // The request expired, or the other phone hung up, between it appearing and this tap.
            // Saying so beats a dead button that looks like the app froze.
            Log.w(TAG, "acceptChatRequest: nothing to accept (id=$id, socket=${activeSocket != null}, state=${_connState.value})")
            _lastFailureReason.value = "That request is no longer open — the other phone hung up or it timed out."
            if (!connectionInProgress()) resumeDiscoveryIfWanted()
            return false
        }
        Log.i(TAG, "acceptChatRequest: accepting $id")
        incomingRequestId = null
        sendWire(ChatWireMessage.ChatAccept(id))
        promoteToConnected()
        return true
    }

    /** Called when this user taps Deny. Tells the peer, then drops the socket cleanly. */
    fun denyChatRequest() {
        val id = incomingRequestId ?: run {
            Log.d(TAG, "denyChatRequest: nothing pending to deny")
            return
        }
        Log.i(TAG, "denyChatRequest: denying $id")
        incomingRequestId = null
        sendWire(ChatWireMessage.ChatDeny(id))
        // Give the frame a moment to flush before tearing the socket down.
        scope.launch {
            delay(300)
            disconnect()
        }
    }

    /**
     * The single exit for a failed connection attempt: publish FAILED with a reason the user can
     * act on, release the connection slot, and put discovery back so the nearby list keeps working
     * instead of freezing behind a failure banner.
     */
    private fun failConnection(reason: String) {
        Log.w(TAG, "failConnection: $reason")
        _lastFailureReason.value = reason
        _connState.value = BtChatConnState.FAILED
        connectionSlot.set(false)
        resumeDiscoveryIfWanted()
    }

    /**
     * Re-dials the device the user last tapped Connect on. Returns false when there is nothing to
     * retry — the caller shows that honestly rather than pretending a retry is in flight.
     */
    fun retryLastConnect(): Boolean {
        val address = lastConnectAddress
        if (address == null) {
            Log.d(TAG, "retryLastConnect: nothing to retry — no previous connect target")
            return false
        }
        if (connectionSlot.get()) {
            Log.d(TAG, "retryLastConnect: an attempt is already in progress (${_connState.value})")
            return false
        }
        Log.i(TAG, "retryLastConnect: retrying $address")
        _lastFailureReason.value = null
        _connState.value = BtChatConnState.IDLE
        connectTo(address)
        return true
    }

    /** The one place CONNECTED is set — reached only after a real accept on both sides. */
    private fun promoteToConnected() {
        requestTimeoutJob?.cancel(); requestTimeoutJob = null
        _connectedDeviceAddress.value = _pendingPeerAddress.value
        _connectedDeviceName.value = _pendingPeerName.value
        _connectedPeerNodeId.value = _pendingPeerIdentity?.nodeId
        _connectedPeerPublicKeyB64.value =
            _pendingPeerIdentity?.let { Base64.encodeToString(it.publicKeyBytes, Base64.NO_WRAP) }
        _sessionSafetyCode.value = sessionKey?.safetyCode
        _lastFailureReason.value = null
        _connState.value = BtChatConnState.CONNECTED
        Log.i(TAG, "promoteToConnected: chat is live with ${_connectedDeviceName.value}")
    }

    /** Fire-and-forget: builds this device's identity frame off the socket thread and sends it like
     *  any other message. Nothing waits on it and nothing breaks if it fails. */
    private fun sendOwnMeshIdentity() {
        scope.launch {
            val mine = runCatching { MeshIdentityStore.ensure(settingsRepository) }.getOrElse {
                Log.w(TAG, "sendOwnMeshIdentity: couldn't load this device's identity — chat continues without mesh reachability", it)
                return@launch
            }
            sendWire(ChatWireMessage.MeshIdentity(MeshIdentityInfo(mine.nodeId, mine.publicKeyBytes).encode()))
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
                    // Messages are only accepted once the chat has actually been agreed. A peer
                    // that tries to talk before an accept is ignored rather than trusted.
                    if (_connState.value != BtChatConnState.CONNECTED) {
                        Log.w(TAG, "readLoop: dropping message received before the chat was accepted")
                    } else {
                        _events.emit(ChatEvent.MessageReceived(wire.id, wire.body, System.currentTimeMillis()))
                        sendWire(ChatWireMessage.Ack(wire.id))
                    }
                }
                is ChatWireMessage.Ack -> _events.emit(ChatEvent.MessageDelivered(wire.id))
                ChatWireMessage.Typing -> _events.emit(ChatEvent.PeerTyping)
                is ChatWireMessage.ChatRequest -> {
                    // Ignore a duplicate/second request on an already-decided session so two
                    // simultaneous requests can't leave the two sides in different states.
                    if (incomingRequestId != null || _connState.value == BtChatConnState.CONNECTED) {
                        Log.d(TAG, "readLoop: ignoring duplicate chat request ${wire.id}")
                    } else {
                        Log.i(TAG, "readLoop: chat request from \"${wire.displayName}\"")
                        incomingRequestId = wire.id
                        _connState.value = BtChatConnState.REQUEST_RECEIVED
                        _events.emit(ChatEvent.ChatRequested(wire.id, wire.displayName))
                    }
                }
                is ChatWireMessage.ChatAccept -> {
                    if (wire.id == outgoingRequestId) {
                        outgoingRequestId = null
                        promoteToConnected()
                        _events.emit(ChatEvent.ChatAccepted)
                    }
                }
                is ChatWireMessage.ChatDeny -> {
                    if (wire.id == outgoingRequestId) {
                        Log.i(TAG, "readLoop: peer denied the chat request")
                        outgoingRequestId = null
                        requestTimeoutJob?.cancel(); requestTimeoutJob = null
                        _lastFailureReason.value = "They declined the chat request."
                        _connState.value = BtChatConnState.DENIED
                        _events.emit(ChatEvent.ChatDenied)
                        disconnect(resetState = false)
                        return
                    }
                }
                is ChatWireMessage.MeshIdentity -> {
                    val info = runCatching { MeshIdentityInfo.decode(wire.body) }.getOrNull()
                    if (info == null) {
                        Log.w(TAG, "readLoop: peer sent an unreadable mesh identity — ignoring")
                    } else {
                        _pendingPeerIdentity = info
                        // If the chat is already live, publish it immediately so History records
                        // this contact as mesh-reachable without waiting for the next connection.
                        if (_connState.value == BtChatConnState.CONNECTED) {
                            _connectedPeerNodeId.value = info.nodeId
                            _connectedPeerPublicKeyB64.value =
                                Base64.encodeToString(info.publicKeyBytes, Base64.NO_WRAP)
                        }
                        Log.d(TAG, "readLoop: learned peer mesh identity ${info.nodeId.take(8)}…")
                    }
                }
                null -> Unit
            }
        }
        onSessionEnded()
    }

    private suspend fun onSessionEnded() {
        Log.i(TAG, "onSessionEnded: peer disconnected or socket closed")
        // Preserve a terminal state the user still needs to read (they denied us, or we timed out);
        // otherwise the socket closing is just an ordinary end-of-session.
        val terminal = ChatStateRules.isTerminalExplanation(_connState.value)
        _events.emit(ChatEvent.PeerDisconnected)
        activeSocket = null
        sessionKey = null
        requestTimeoutJob?.cancel(); requestTimeoutJob = null
        outgoingRequestId = null
        incomingRequestId = null
        _pendingPeerIdentity = null
        _pendingPeerName.value = null
        _pendingPeerAddress.value = null
        _connectedDeviceName.value = null
        _connectedDeviceAddress.value = null
        _connectedPeerNodeId.value = null
        _connectedPeerPublicKeyB64.value = null
        _sessionSafetyCode.value = null
        if (!terminal) _connState.value = BtChatConnState.IDLE
        connectionSlot.set(false)
        resumeDiscoveryIfWanted()
    }

    /**
     * Sends a text message and returns its id (used to track delivery via the peer's Ack), or null
     * if there is no accepted, live session to send it over. Returning null instead of throwing is
     * what lets the caller show "not connected" rather than crash — the Send button used to assume
     * this always produced an id.
     */
    fun sendText(text: String): String? {
        if (!ChatStateRules.canSendText(_connState.value, activeSocket != null, sessionKey != null)) {
            Log.w(TAG, "sendText: refused — no accepted, live chat session (state=${_connState.value}, socket=${activeSocket != null}, key=${sessionKey != null})")
            return null
        }
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

    /**
     * Drops the session. [resetState] is false when the caller has already published a terminal
     * state the user still needs to see (DENIED, REQUEST_TIMEOUT) — overwriting it with IDLE here
     * would wipe the explanation off the screen before it could be read.
     */
    fun disconnect(resetState: Boolean = true) {
        requestTimeoutJob?.cancel(); requestTimeoutJob = null
        outgoingRequestId = null
        incomingRequestId = null
        _pendingPeerIdentity = null
        _pendingPeerName.value = null
        _pendingPeerAddress.value = null
        runCatching { activeSocket?.close() }
        activeSocket = null
        sessionKey = null
        _connectedDeviceName.value = null
        _connectedDeviceAddress.value = null
        _connectedPeerNodeId.value = null
        _connectedPeerPublicKeyB64.value = null
        _sessionSafetyCode.value = null
        if (resetState) _connState.value = BtChatConnState.IDLE
        connectionSlot.set(false)
        resumeDiscoveryIfWanted()
    }

    /** Stops the server socket, BLE advertising and scanning — call when leaving Chat entirely. */
    fun shutdown() {
        Log.d(TAG, "shutdown: tearing down chat session, listener, scan and advertising")
        MeshRelayManager.foregroundBleActive = false
        // Clear the intent to keep scanning *before* disconnect(), so the session teardown's
        // resumeDiscoveryIfWanted() has nothing to re-arm on the way out.
        stopDiscovery()
        disconnect()
        serverJob?.cancel()
        serverJob = null
        stopAdvertising()
        adapterStateReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        adapterStateReceiver = null
        _canAdvertise.value = null
        _advertisePermissionMissing.value = false
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
        /** Hardening: a hostile peer could previously declare a 1 MiB frame and make this side
         *  allocate it before any authentication, repeatedly — cheap memory-pressure DoS. Chat
         *  frames are short text, so this bounds it hard. */
        private const val MAX_FRAME_BYTES = 65_536

        /** BLE presence/discovery UUID — distinct from [APP_UUID] above (that one is the classic
         *  RFCOMM channel the actual chat runs over once connected). This one only ever appears in
         *  a BLE advertisement, and only Thread Protection advertises it, which is what makes
         *  "Tap to scan" show app instances instead of every nearby Bluetooth device. */
        private val PRESENCE_SERVICE_UUID: UUID = UUID.fromString("9f9f2f9f-303f-4071-beee-32741e782946")
        private const val MAX_ADVERTISED_NAME_BYTES = 13
        private const val SCAN_WINDOW_MS = 20_000L

        /** Brief pause between continuous-scan windows — lets the radio breathe between bursts. */
        private const val SCAN_REARM_GAP_MS = 1_500L
        private const val STALE_AFTER_MS = 8_000L
        private const val STALE_SWEEP_INTERVAL_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** How long the initiator waits for Accept/Deny before giving up and cleaning up. */
        private const val REQUEST_TIMEOUT_MS = 45_000L

        /** Upper bound on the ML-KEM handshake. Generous enough for a slow RFCOMM link on a busy
         *  radio, short enough that a peer that connects and says nothing doesn't wedge the accept
         *  loop for the rest of the session. */
        private const val HANDSHAKE_TIMEOUT_MS = 12_000L

        /** `adb logcat -s TPChat` to trace the whole discover → connect → handshake flow on a real
         *  device: adapter state, permission checks, advertising start/failure, scan start/stop,
         *  every peer sighting and staleness drop, connection attempts and handshake outcome. */
        const val TAG = "TPChat"

        @Volatile private var instance: BluetoothChatManager? = null

        /**
         * One instance per process. This has to be shared, not per-ViewModel: the foreground
         * service keeps the RFCOMM listener alive so this phone stays reachable when Chat isn't
         * on screen, and the notification's Accept/Deny buttons arrive on a BroadcastReceiver
         * with no ViewModel at all. All three must be driving the same connection.
         */
        fun getInstance(context: Context, settingsRepository: SettingsRepository): BluetoothChatManager =
            instance ?: synchronized(this) {
                instance ?: BluetoothChatManager(context.applicationContext, settingsRepository).also { instance = it }
            }
    }
}
