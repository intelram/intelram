package com.intelram.netsteer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.intelram.netsteer.core.BoostController
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

data class LinkStatus(val label: String, val up: Boolean, val rxBps: Double = 0.0, val txBps: Double = 0.0, val connections: Int = 0)

data class CombineStatus(
    val running: Boolean = false,
    val links: List<LinkStatus> = emptyList(),
    val totalBps: Double = 0.0,
    val connections: Int = 0,
    val combine: Boolean = false,
    val smart: Boolean = false,
    val boosting: Boolean = false,
    /** Mobile data is being held connected by us. */
    val mobileHeld: Boolean = false,
    /** New connections may use mobile right now. */
    val mobileActive: Boolean = false,
    val adBlock: Boolean = false,
    val blockedToday: Long = 0,
    val message: String? = null,
)

/**
 * On-device VPN: Wi-Fi + mobile combining and ad blocking.
 *
 *   apps ──▶ TUN (this VPN) ──▶ hev-socks5-tunnel (native TCP/IP stack)
 *        ──▶ local SOCKS5 [ProxyServer] ──▶ ad filter ──▶ per-connection choice of Wi-Fi or mobile
 *
 * Battery: mobile data is only held up while [BoostController] sees sustained
 * heavy traffic (unless the user turned smart mode off); with the screen off
 * the service samples every 5 s and never redraws the notification.
 */
class CombineVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app get() = application as NetSteerApp
    private lateinit var notifier: StatusNotifier
    private lateinit var power: PowerManager

    private var tun: ParcelFileDescriptor? = null
    private var proxy: ProxyServer? = null
    private var nativeStarted = false
    private val jobs = mutableListOf<Job>()
    private val uplinkCache = HashMap<String, AndroidUplink>()
    private val updatingFilters = AtomicBoolean(false)

    /** Set by the boost controller: may new connections use mobile data? */
    @Volatile private var mobileForNew = true

    override fun onCreate() {
        super.onCreate()
        notifier = StatusNotifier(this)
        power = getSystemService(PowerManager::class.java)!!
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn("Stopped")
            return START_NOT_STICKY
        }
        // Must be in the foreground within seconds of startForegroundService().
        goForeground(CombineStatus(message = "Starting…"))
        if (tun == null) startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN was started, or the user revoked permission in Settings.
        stopVpn("VPN permission revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn(null)
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

    private fun startVpn() {
        if (prepare(this) != null) {
            stopVpn("VPN permission not granted. Open NetSteer and tap Turn on.")
            return
        }
        val user = randomToken()
        val pass = randomToken()
        val scheduler = LinkScheduler()
        val meter = TrafficMeter()
        val boost = BoostController()
        val px = ProxyServer(
            uplinks = ::uplinksForNewConnections,
            scheduler = scheduler,
            meter = meter,
            user = user,
            pass = pass,
            stickyMode = { app.settings.current.compat },
            connectTimeoutMs = 5_000,
            filter = { app.filters.hostFilter() },
            onBlocked = { app.filters.recordBlocked(it) },
            log = { Log.w(TAG, it) },
        )
        val port = try {
            px.start()
        } catch (e: Exception) {
            Log.e(TAG, "proxy start failed", e)
            stopVpn("Could not start local proxy: ${e.message}")
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
                .setUnderlyingNetworks(allUplinks().map { it.network }.toTypedArray().ifEmpty { null })
                .setConfigureIntent(
                    PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                )
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (pfd == null) {
            stopVpn("Android refused to start the VPN (another VPN may be set as always-on).")
            return
        }
        tun = pfd

        val conf = File(noBackupFilesDir, "tunnel.yml")
        // Debug builds keep hev's warnings in app-private storage for troubleshooting.
        val logFile = File(noBackupFilesDir, "tunnel.log").takeIf { app.debuggable }?.also { it.delete() }
        conf.writeText(tunnelConfig(port, user, pass, logFile?.absolutePath))
        val started = try {
            TProxy.TProxyStartService(conf.absolutePath, pfd.fd)
        } catch (t: Throwable) { // UnsatisfiedLinkError if the native library is missing for this CPU
            Log.e(TAG, "native tunnel start failed", t)
            false
        }
        if (!started) {
            stopVpn("Native tunnel failed to start")
            return
        }
        nativeStarted = true
        Log.i(TAG, "vpn started, proxy port $port")

        jobs += scope.launch {
            // Keep the OS informed which networks carry VPN traffic (data accounting, metering).
            app.monitor.networks
                .map { allUplinks().associate { it.id to it.network } }
                .distinctUntilChanged()
                .collect { links ->
                    Log.i(TAG, "uplinks: ${links.keys.sorted()}")
                    runCatching { setUnderlyingNetworks(links.values.toTypedArray().ifEmpty { null }) }
                }
        }
        jobs += scope.launch { ticker(px, scheduler, meter, boost) }
        jobs += scope.launch {
            // Filter lists: refresh at start (if stale) and check again every 6 hours.
            while (isActive) {
                maybeUpdateFilters(force = false)
                delay(6 * 3600_000L)
            }
        }
    }

    private suspend fun ticker(px: ProxyServer, scheduler: LinkScheduler, meter: TrafficMeter, boost: BoostController) {
        var lastNotified = ""
        var lastNotifyAt = 0L
        var lastLogAt = 0L
        var lastStatsSave = System.currentTimeMillis()
        while (true) {
            val interactive = power.isInteractive
            delay(if (interactive) 1_000 else 5_000)
            val now = System.currentTimeMillis()
            val rates = meter.sample(now)
            val s = app.settings.current
            val up = allUplinks().map { it.id }.toSet()
            val links = listOf(WIFI to "Wi-Fi", CELL to "Mobile").map { (id, label) ->
                val r = rates[id]
                LinkStatus(label, id in up, r?.rxBps ?: 0.0, r?.txBps ?: 0.0, scheduler.active(id))
            }
            val total = links.sumOf { it.rxBps + it.txBps }

            val d = boost.update(now, total, wifiUp = WIFI in up, combine = s.combine, smart = s.smart)
            mobileForNew = d.mobileForNewConnections
            app.monitor.setMobileRequested(d.requestMobile)

            val status = CombineStatus(
                running = true,
                links = links,
                totalBps = total,
                connections = px.activeConnections,
                combine = s.combine,
                smart = s.smart,
                boosting = d.boosting,
                mobileHeld = d.requestMobile,
                mobileActive = d.mobileForNewConnections && CELL in up,
                adBlock = s.adBlock,
                blockedToday = app.filters.blockedToday.get(),
            )
            _status.value = status

            // The notification is invisible with the screen off: don't spend CPU redrawing it.
            if (interactive && now - lastNotifyAt >= 2_000) {
                val key = notifier.contentKey(status)
                if (key != lastNotified) {
                    runCatching { notifier.update(status) }
                    lastNotified = key
                }
                lastNotifyAt = now
            }
            if (now - lastLogAt >= 5_000) {
                lastLogAt = now
                // Machine-readable line, used by the emulator end-to-end test.
                Log.i(TAG, "stats " + listOf(WIFI, CELL).joinToString(" ") { id ->
                    "$id=${rates[id]?.rxTotal ?: 0}/${rates[id]?.txTotal ?: 0}"
                } + " opened=" + listOf(WIFI, CELL).joinToString(",") { id -> "$id:${rates[id]?.opened ?: 0}" } +
                    " conns=${px.activeConnections} failed=${px.failedConnections}" +
                    " boost=${d.boosting} mobileReq=${d.requestMobile} blocked=${app.filters.blockedTotal.get()}")
            }
            if (now - lastStatsSave >= 60_000) {
                app.filters.saveStats()
                lastStatsSave = now
            }
        }
    }

    /** Download filter lists when stale, preferring Wi-Fi so mobile data isn't spent on it. */
    fun maybeUpdateFilters(force: Boolean) {
        if (!app.settings.current.adBlock && !force) return
        if (!force && !app.filters.needsUpdate()) return
        val wifi = allUplinks().firstOrNull { it.id == WIFI }?.network
        val network: Network? = wifi ?: if (force || !app.filters.hasAnyDownloadedList()) allUplinks().firstOrNull()?.network else null
        if (network == null) return
        if (!updatingFilters.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                app.filters.update(network, force)
            } finally {
                updatingFilters.set(false)
            }
        }
    }

    private fun stopVpn(message: String?) {
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
        app.monitor.setMobileRequested(false)
        if (wasRunning) app.filters.saveStats()
        _status.value = CombineStatus(running = false, message = message)
        if (wasRunning) Log.i(TAG, "vpn stopped: $message")
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(StatusNotifier.ID) }
        stopSelf()
    }

    /** Links offered to new connections: mobile is withheld unless combining/boosting wants it. */
    private fun uplinksForNewConnections(): List<AndroidUplink> {
        val all = allUplinks()
        if (mobileForNew) return all
        return all.filter { it.id != CELL }.ifEmpty { all } // never strand traffic if only mobile is up
    }

    /** Validated Wi-Fi and mobile networks, as uplinks with stable ids ("wifi", "cell"). */
    private fun allUplinks(): List<AndroidUplink> {
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

    private fun tunnelConfig(port: Int, user: String, pass: String, logPath: String?) = """
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
        |  connect-timeout: 45000
        |  tcp-read-write-timeout: 7200000
        |  udp-read-write-timeout: 60000
        |  max-session-count: 1500
        |  log-level: warn
        |  log-file: '${logPath ?: "null"}'
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
