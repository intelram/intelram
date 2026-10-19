package com.intelram.netsteer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.intelram.netsteer.core.LinkScheduler
import com.intelram.netsteer.core.ProxyServer
import com.intelram.netsteer.core.TrafficMeter
import com.intelram.netsteer.core.Transport
import com.intelram.netsteer.tunnel.TProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

data class LinkStatus(val label: String, val up: Boolean, val rxBps: Double = 0.0, val txBps: Double = 0.0, val connections: Int = 0)

data class CombineStatus(
    val running: Boolean = false,
    val links: List<LinkStatus> = emptyList(),
    val totalBps: Double = 0.0,
    val connections: Int = 0,
    val message: String? = null,
)

/**
 * Device-wide Wi-Fi + mobile combining.
 *
 *   apps ──▶ TUN (this VPN) ──▶ hev-socks5-tunnel (native TCP/IP stack)
 *        ──▶ local SOCKS5 [ProxyServer] ──▶ per-connection choice of Wi-Fi or mobile
 *
 * Every new connection is placed on the less-loaded link (weighted by measured
 * speed), so traffic that uses several connections gets both links' bandwidth.
 */
class CombineVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app get() = application as NetSteerApp
    private lateinit var notifier: StatusNotifier

    private var tun: ParcelFileDescriptor? = null
    private var nativeStarted = false
    private var proxy: ProxyServer? = null
    private var jobs = mutableListOf<Job>()
    private val uplinkCache = HashMap<String, AndroidUplink>()

    override fun onCreate() {
        super.onCreate()
        notifier = StatusNotifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCombining("Stopped")
            return START_NOT_STICKY
        }
        // Must be in the foreground within seconds of startForegroundService().
        goForeground(CombineStatus(message = "Starting…"))
        if (tun == null) startCombining()
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN was started or the user revoked permission in Settings.
        stopCombining("VPN permission revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopCombining(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(status: CombineStatus) {
        val n = notifier.build(status)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(StatusNotifier.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(StatusNotifier.ID, n)
        }
    }

    private fun startCombining() {
        if (prepare(this) != null) {
            stopCombining("VPN permission not granted. Open NetSteer and tap Start.")
            return
        }
        app.monitor.start(hotStandby = true) // keep mobile data up while Wi-Fi is connected

        val user = randomToken()
        val pass = randomToken()
        val scheduler = LinkScheduler()
        val meter = TrafficMeter()
        val px = ProxyServer(
            uplinks = ::currentUplinks,
            scheduler = scheduler,
            meter = meter,
            user = user,
            pass = pass,
            stickyMode = { app.compatibilityMode },
            connectTimeoutMs = 5_000,
            log = { Log.w(TAG, it) },
        )
        val port = try {
            px.start()
        } catch (e: Exception) {
            Log.e(TAG, "proxy start failed", e)
            stopCombining("Could not start local proxy: ${e.message}")
            return
        }
        proxy = px

        val pfd = try {
            Builder()
                .setSession("NetSteer")
                .setMtu(MTU)
                .addAddress(TUN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(MAPPED_DNS)
                .addDisallowedApplication(packageName) // our own sockets go straight to Wi-Fi/mobile
                .setBlocking(false)
                .setMetered(false) // inherit meteredness from the underlying networks
                .setUnderlyingNetworks(currentUplinks().map { it.network }.toTypedArray().ifEmpty { null })
                .setConfigureIntent(
                    PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                )
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (pfd == null) {
            stopCombining("Android refused to start the VPN (another VPN may be always-on).")
            return
        }
        tun = pfd

        val conf = File(noBackupFilesDir, "tunnel.yml")
        conf.writeText(tunnelConfig(port, user, pass))
        val started = try {
            TProxy.TProxyStartService(conf.absolutePath, pfd.fd)
        } catch (t: Throwable) { // UnsatisfiedLinkError if the native library is missing for this CPU
            Log.e(TAG, "native tunnel start failed", t)
            false
        }
        if (!started) {
            stopCombining("Native tunnel failed to start")
            return
        }
        nativeStarted = true
        Log.i(TAG, "combining started, proxy port $port")

        jobs += scope.launch {
            // Keep the OS informed which networks carry VPN traffic (data accounting, metering).
            app.monitor.networks
                .map { currentUplinks().map { it.network }.toSet() }
                .distinctUntilChanged()
                .collect { nets -> runCatching { setUnderlyingNetworks(nets.toTypedArray().ifEmpty { null }) } }
        }
        jobs += scope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                val rates = meter.sample(System.currentTimeMillis())
                val up = currentUplinks().map { it.id }.toSet()
                val links = listOf(WIFI to "Wi-Fi", CELL to "Mobile").map { (id, label) ->
                    val r = rates[id]
                    LinkStatus(label, id in up, r?.rxBps ?: 0.0, r?.txBps ?: 0.0, scheduler.active(id))
                }
                val s = CombineStatus(
                    running = true,
                    links = links,
                    totalBps = links.sumOf { it.rxBps + it.txBps },
                    connections = px.activeConnections,
                )
                _status.value = s
                if (tick % 2 == 0) runCatching { notifier.update(s) }
                if (tick % 5 == 0) {
                    // Machine-readable line, used by the emulator end-to-end test.
                    Log.i(TAG, "stats " + listOf(WIFI, CELL).joinToString(" ") { id ->
                        "$id=${rates[id]?.rxTotal ?: 0}/${rates[id]?.txTotal ?: 0}"
                    } + " conns=${px.activeConnections} failed=${px.failedConnections}")
                }
                tick++
            }
        }
    }

    private fun stopCombining(message: String?) {
        // Join the ticker so it cannot re-post the notification or status after we clear them.
        runBlocking { withTimeoutOrNull(2_000) { jobs.forEach { it.cancelAndJoin() } } }
        jobs.clear()
        val wasRunning = tun != null
        if (nativeStarted) runCatching { TProxy.TProxyStopService() }
        nativeStarted = false
        runCatching { tun?.close() }
        tun = null
        proxy?.close()
        proxy = null
        synchronized(uplinkCache) { uplinkCache.clear() }
        app.monitor.start(hotStandby = false) // release the mobile-data request
        _status.value = CombineStatus(running = false, message = message)
        if (wasRunning) Log.i(TAG, "combining stopped: $message")
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(StatusNotifier.ID) }
        stopSelf()
    }

    /** Validated Wi-Fi and mobile networks, as uplinks with stable ids ("wifi", "cell"). */
    private fun currentUplinks(): List<AndroidUplink> {
        val states = app.monitor.networks.value.values
            .filter { it.toSnapshot().let { s -> s.validated && !s.captivePortal } }
        val byId = LinkedHashMap<String, Network>()
        for (s in states) {
            val id = when (s.transport) {
                Transport.WIFI -> WIFI
                Transport.CELLULAR -> CELL
                else -> continue
            }
            byId.putIfAbsent(id, s.network)
        }
        synchronized(uplinkCache) {
            return byId.map { (id, net) ->
                uplinkCache[id]?.takeIf { it.network == net } ?: AndroidUplink(net, id).also { uplinkCache[id] = it }
            }
        }
    }

    private fun tunnelConfig(port: Int, user: String, pass: String) = """
        |tunnel:
        |  mtu: $MTU
        |socks5:
        |  port: $port
        |  address: '127.0.0.1'
        |  udp: 'tcp'
        |  username: '$user'
        |  password: '$pass'
        |mapdns:
        |  address: $MAPPED_DNS
        |  port: 53
        |  network: 198.19.0.0
        |  netmask: 255.255.0.0
        |  cache-size: 10000
        |misc:
        |  connect-timeout: 30000
        |  tcp-read-write-timeout: 300000
        |  udp-read-write-timeout: 60000
        |  log-level: warn
        |""".trimMargin()

    companion object {
        const val ACTION_START = "com.intelram.netsteer.START"
        const val ACTION_STOP = "com.intelram.netsteer.STOP"
        const val TAG = "NetSteer"
        const val WIFI = "wifi"
        const val CELL = "cell"
        private const val MTU = 8500
        private const val TUN_ADDRESS = "198.18.0.1"
        /** Answered inside hev with fake IPs from 198.19.0.0/16, so names are resolved on the chosen link. */
        private const val MAPPED_DNS = "198.18.0.2"

        private val _status = MutableStateFlow(CombineStatus())
        val status: StateFlow<CombineStatus> = _status.asStateFlow()

        private fun randomToken(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val rnd = SecureRandom()
            return String(CharArray(24) { alphabet[rnd.nextInt(alphabet.length)] })
        }
    }
}
