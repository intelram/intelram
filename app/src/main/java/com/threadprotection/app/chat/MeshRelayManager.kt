package com.threadprotection.app.chat

import android.Manifest
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
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.threadprotection.app.crypto.PqcChatCrypto
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.data.StoredMeshEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** A mesh-relayed message that arrived addressed to this device — either handed straight to us by
 *  its original sender, or carried here across one or more intermediate phones. */
data class MeshDeliveredMessage(val senderNodeId: String, val senderName: String, val body: String, val sentAtMs: Long)

/**
 * Store-and-forward mesh relay over Bluetooth Classic: if you and the person you're messaging
 * aren't in range of each other, but you're each in range of *some* phone running Thread
 * Protection — even a stranger's, even several hops apart — the message hops phone to phone until
 * it reaches them. This is the same delay-tolerant-networking technique real offline mesh chat
 * apps (Bridgefy, FireChat-era apps) use: no internet, no fixed infrastructure, just opportunistic
 * contact between nearby devices.
 *
 * Privacy model, stated precisely rather than oversold: content and the sender's identity are
 * end-to-end encrypted (ML-KEM-768 + AES-256-GCM, sealed against the *destination's* long-term
 * public key — see MeshEnvelope/MeshPayload) so no relay along the path can read the message or
 * learn who sent it. What a relay *can* see is which device the message is addressed to, since
 * that's unavoidable for routing without a full onion-routing layer (out of scope here) — this is
 * the same trade-off every practical mesh-messaging app makes. Delivery is best-effort: messages
 * expire after 24h uncarried, there are no delivery receipts across relay hops (only a live direct
 * connection gets those), and reach depends entirely on whether carriers happen to cross paths.
 *
 * Requires having met the recipient directly at least once before (their long-term public key is
 * only learned during BluetoothChatManager's own handshake) — you can't relay to a stranger you've
 * never actually connected to.
 *
 * One more honest limitation: since there's no return receipt, the *sender's* own device has no
 * way to know a message actually arrived, so it keeps offering that same envelope to peers it
 * meets until the normal 24h expiry — harmless (peers just dedupe it by msgId) but not "smart"
 * about stopping early once delivery has, in fact, already happened.
 */
class MeshRelayManager private constructor(private val context: Context, private val settingsRepository: SettingsRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var activeServerSocket: BluetoothServerSocket? = null

    private val _delivered = MutableSharedFlow<MeshDeliveredMessage>(extraBufferCapacity = 16)
    val delivered: SharedFlow<MeshDeliveredMessage> = _delivered.asSharedFlow()

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @Volatile private var cachedIdentity: MeshIdentity? = null

    /** The identity never changes once generated, so this resolves it from DataStore at most
     *  once per process — not on every 90s tick. */
    private suspend fun identity(): MeshIdentity =
        cachedIdentity ?: MeshIdentityStore.ensure(settingsRepository).also { cachedIdentity = it }

    /** Starts accepting inbound gossip connections from other carriers — call once when real-time
     *  protection turns on, matching the "always relaying in the background" behaviour. */
    fun startListening() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            while (true) {
                val a = adapter
                if (a == null || !a.isEnabled) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                val serverSocket = runCatching {
                    a.listenUsingInsecureRfcommWithServiceRecord(MESH_SERVICE_NAME, MESH_UUID)
                }.getOrNull()
                if (serverSocket == null) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                activeServerSocket = serverSocket
                // Returns once the socket is closed — either by stopListening() (intentional stop)
                // or a real I/O error — either way, loop back and try listening again.
                acceptLoop(serverSocket)
            }
        }
    }

    /** Closing the server socket is what actually unblocks a thread parked in the blocking
     *  `accept()` call below — cancelling the coroutine job alone wouldn't, since accept() isn't a
     *  suspending cancellation point. */
    fun stopListening() {
        serverJob?.cancel()
        serverJob = null
        activeServerSocket?.let { runCatching { it.close() } }
        activeServerSocket = null
    }

    private suspend fun acceptLoop(serverSocket: BluetoothServerSocket) {
        while (true) {
            val socket = runCatching { serverSocket.accept() }.getOrNull() ?: return
            scope.launch {
                runCatching {
                    withTimeoutOrNull(GOSSIP_TIMEOUT_MS) { performGossip(socket, isInitiator = false) }
                }
                runCatching { socket.close() }
            }
        }
    }

    /** Seals a message for [destNodeId] using their stored long-term public key and drops it into
     *  this device's own carry-store — from here it propagates exactly like a relayed message,
     *  starting its own journey on the very next gossip tick (including immediate direct delivery
     *  if the destination happens to already be in range). */
    suspend fun queueOutbound(destNodeId: String, destPublicKeyBytes: ByteArray, senderName: String, body: String) {
        val (kemCiphertext, sessionKey) = PqcChatCrypto.encapsulate(destPublicKeyBytes)
        val identity = identity()
        val payload = MeshPayload(identity.nodeId, senderName, System.currentTimeMillis(), body).encode()
        val aead = PqcChatCrypto.encrypt(sessionKey, payload)
        val envelope = MeshEnvelope(UUID.randomUUID().toString(), destNodeId, kemCiphertext, aead)
        settingsRepository.mergeMeshEnvelopes(
            listOf(
                StoredMeshEnvelope(
                    msgId = envelope.msgId,
                    destNodeId = destNodeId,
                    ttl = MAX_HOPS,
                    envelopeB64 = Base64.encodeToString(envelope.encode(), Base64.NO_WRAP),
                    receivedAtMs = System.currentTimeMillis(),
                ),
            ),
        )
    }

    /** One gossip cycle — call periodically from the foreground service (see
     *  ProtectionForegroundService). Briefly scans for nearby devices and, for any reachable one,
     *  swaps whatever envelopes each side doesn't already have. */
    suspend fun tick() {
        val a = adapter ?: return
        if (!a.isEnabled || !hasScanPermission()) return
        // A classic Bluetooth inquiry monopolises the radio and badly degrades — often entirely
        // blocks — concurrent BLE scanning and advertising on the same chip. Skipping this cycle
        // while the user is actively looking for people in Chat is the difference between
        // "Tap to scan" reliably finding a peer and it intermittently finding nothing at all,
        // depending purely on whether a 90-second background tick happened to overlap.
        if (foregroundBleActive) {
            Log.d(TAG, "tick: skipping gossip — Chat is using the BLE radio right now")
            return
        }
        identity() // make sure an identity exists before we might need to route to it
        val peers = discoverBriefly(a)
        for (device in peers) {
            runCatching { gossipWith(device) }
        }
    }

    private suspend fun gossipWith(device: BluetoothDevice) {
        val socket = runCatching { device.createInsecureRfcommSocketToServiceRecord(MESH_UUID) }.getOrNull() ?: return
        withTimeoutOrNull(GOSSIP_TIMEOUT_MS) {
            runCatching { socket.connect() }.getOrElse { return@withTimeoutOrNull }
            performGossip(socket, isInitiator = true)
        }
        runCatching { socket.close() }
    }

    private suspend fun performGossip(socket: BluetoothSocket, isInitiator: Boolean) {
        val out = DataOutputStream(socket.outputStream)
        val input = DataInputStream(socket.inputStream)

        if (isInitiator) {
            writeFrame(out, MESH_MAGIC)
            val peerMagic = readFrame(input) ?: return
            if (!peerMagic.contentEquals(MESH_MAGIC)) return
        } else {
            val peerMagic = readFrame(input) ?: return
            if (!peerMagic.contentEquals(MESH_MAGIC)) return
            writeFrame(out, MESH_MAGIC)
        }

        val mine = settingsRepository.meshOutboxOnce()
        val myIds = mine.map { it.msgId }.toSet()

        val theirIds: Set<String> = if (isInitiator) {
            writeIdList(out, myIds)
            readIdList(input)
        } else {
            readIdList(input).also { writeIdList(out, myIds) }
        }

        val toSend = mine.filter { it.msgId !in theirIds }
        val received: List<Pair<Int, ByteArray>> = if (isInitiator) {
            writeEnvelopes(out, toSend)
            readEnvelopes(input)
        } else {
            readEnvelopes(input).also { writeEnvelopes(out, toSend) }
        }

        if (received.isNotEmpty()) ingest(received)
    }

    private suspend fun ingest(received: List<Pair<Int, ByteArray>>) {
        val identity = identity()
        val toCarry = mutableListOf<StoredMeshEnvelope>()
        for ((ttl, envelopeBytes) in received) {
            val envelope = MeshEnvelope.decode(envelopeBytes) ?: continue
            if (envelope.destNodeId == identity.nodeId) {
                val sessionKey = runCatching { PqcChatCrypto.decapsulate(identity.privateKey, envelope.kemCiphertext) }.getOrNull() ?: continue
                val plaintext = runCatching { PqcChatCrypto.decrypt(sessionKey, envelope.aeadPayload) }.getOrNull() ?: continue
                val payload = MeshPayload.decode(plaintext) ?: continue
                _delivered.emit(MeshDeliveredMessage(payload.senderNodeId, payload.senderName, payload.body, payload.sentAtMs))
                // delivered — nothing more to carry for this one
            } else if (ttl > 0) {
                toCarry += StoredMeshEnvelope(
                    msgId = envelope.msgId,
                    destNodeId = envelope.destNodeId,
                    ttl = ttl - 1,
                    envelopeB64 = Base64.encodeToString(envelopeBytes, Base64.NO_WRAP),
                    receivedAtMs = System.currentTimeMillis(),
                )
            }
        }
        if (toCarry.isNotEmpty()) settingsRepository.mergeMeshEnvelopes(toCarry)
    }

    private suspend fun discoverBriefly(adapter: BluetoothAdapter): List<BluetoothDevice> {
        val found = mutableListOf<BluetoothDevice>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (device != null) found += device
            }
        }
        runCatching { context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND)) }
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (started) delay(DISCOVERY_WINDOW_MS)
        runCatching { adapter.cancelDiscovery() }
        runCatching { context.unregisterReceiver(receiver) }
        return found.distinctBy { it.address }
    }

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    // ── wire helpers ──

    private fun writeFrame(out: DataOutputStream, data: ByteArray) {
        out.writeInt(data.size)
        out.write(data)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray? = runCatching {
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME_BYTES) return null
        ByteArray(len).also { input.readFully(it) }
    }.getOrNull()

    private fun writeIdList(out: DataOutputStream, ids: Set<String>) {
        val buf = java.io.ByteArrayOutputStream()
        val d = DataOutputStream(buf)
        d.writeShort(ids.size)
        ids.forEach { d.write(MeshEnvelope.uuidToBytes(it)) }
        writeFrame(out, buf.toByteArray())
    }

    private fun readIdList(input: DataInputStream): Set<String> {
        val frame = readFrame(input) ?: return emptySet()
        return runCatching {
            val d = DataInputStream(frame.inputStream())
            val count = d.readUnsignedShort()
            (0 until count).mapNotNull {
                val idBytes = ByteArray(16)
                d.readFully(idBytes)
                MeshEnvelope.bytesToUuid(idBytes)
            }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun writeEnvelopes(out: DataOutputStream, entries: List<StoredMeshEnvelope>) {
        val buf = java.io.ByteArrayOutputStream()
        val d = DataOutputStream(buf)
        d.writeShort(entries.size)
        entries.forEach { entry ->
            val bytes = runCatching { Base64.decode(entry.envelopeB64, Base64.NO_WRAP) }.getOrNull() ?: return@forEach
            d.writeByte(entry.ttl.coerceIn(0, 255))
            d.writeShort(bytes.size)
            d.write(bytes)
        }
        writeFrame(out, buf.toByteArray())
    }

    /** Returns (ttl, rawEnvelopeBytes) pairs — ttl is authoritative for this hop, not whatever's
     *  baked into the envelope (there's nothing baked in — see MeshEnvelope's doc comment). */
    private fun readEnvelopes(input: DataInputStream): List<Pair<Int, ByteArray>> {
        val frame = readFrame(input) ?: return emptyList()
        return runCatching {
            val d = DataInputStream(frame.inputStream())
            val count = d.readUnsignedShort()
            (0 until count).mapNotNull {
                val ttl = d.readUnsignedByte()
                val len = d.readUnsignedShort()
                if (len > MAX_FRAME_BYTES) return@mapNotNull null
                val bytes = ByteArray(len)
                d.readFully(bytes)
                ttl to bytes
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private val MESH_UUID: UUID = UUID.fromString("9f9f2f9f-303f-4071-beee-32741e782945")
        private const val MESH_SERVICE_NAME = "ThreadProtectionMesh"
        private val MESH_MAGIC = "TPMESH1".toByteArray()
        private const val MAX_FRAME_BYTES = 1_048_576
        private const val MAX_HOPS = 8
        private const val DISCOVERY_WINDOW_MS = 9_000L
        private const val GOSSIP_TIMEOUT_MS = 8_000L
        private const val RETRY_DELAY_MS = 30_000L

        private const val TAG = "TPMesh"

        /** Set by BluetoothChatManager while Chat is advertising or scanning over BLE. The mesh
         *  relay's classic-Bluetooth inquiry and BLE share one radio and contend badly, so gossip
         *  cycles stand down for the duration rather than sabotaging live discovery. */
        @Volatile var foregroundBleActive: Boolean = false

        @Volatile private var instance: MeshRelayManager? = null

        /** One instance per process, shared by the always-on foreground service (which actually
         *  drives listening/ticking) and the UI's ViewModel (which sends and observes deliveries)
         *  — they need to see the same `delivered` flow and agree on the same in-flight gossip
         *  state, not two independent relays racing each other. */
        fun getInstance(context: Context, settingsRepository: SettingsRepository): MeshRelayManager =
            instance ?: synchronized(this) {
                instance ?: MeshRelayManager(context.applicationContext, settingsRepository).also { instance = it }
            }
    }
}
