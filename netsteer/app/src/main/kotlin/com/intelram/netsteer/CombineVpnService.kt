package com.intelram.netsteer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.intelram.netsteer.core.BoostController
import com.intelram.netsteer.core.ConnectionMode
import com.intelram.netsteer.core.LinkScheduler
import com.intelram.netsteer.core.ProxyServer
import com.intelram.netsteer.core.TrafficMeter
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Dual Wi-Fi availability, for the UI. */
enum class DualWifi { UNSUPPORTED, OFF, SEARCHING, CONNECTED }

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
    /** Number of links currently taking new connections. */
    val activeLinks: Int = 0,
    val adBlock: Boolean = false,
    val blockedToday: Long = 0,
    val dualWifi: DualWifi = DualWifi.UNSUPPORTED,
    val mode: ConnectionMode = ConnectionMode.OFF,
    /** Usable links held back plus added Wi-Fi networks Android can switch to. */
    val standby: Int = 0,
    val message: String? = null,
)

/**
 * On-device VPN: link combining and ad blocking.
 *
 *   apps ──▶ TUN (this VPN) ──▶ hev-socks5-tunnel (native TCP/IP stack)
 *        ──▶ local SOCKS5 [ProxyServer] ──▶ ad filter ──▶ per-connection choice among
 *            Wi-Fi, a second Wi-Fi (dual-Wi-Fi phones), mobile, USB Ethernet, Bluetooth tethering
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
    private val updatingFilters = AtomicBoolean(false)

    /** Set by the boost controller and the user's mobile toggle: may new connections use mobile data? */
    @Volatile private var mobileForNew = true

    override fun onCreate() {
        super.onCreate()
        notifier = StatusNotifier(this)
        power = getSystemService(PowerManager::class.java)!!
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn("Stopped")
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (tun != null) {
                    teardown()
                    startVpn()
                }
                return START_STICKY
            }
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

        val excluded = app.settings.current.excludedApps
        val pfd = try {
            val b = Builder()
                .setSession("NetSteer")
                .setMtu(MTU)
                .addAddress(TUN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(MAPPED_DNS)
                .addDisallowedApplication(packageName) // our own sockets go straight to the real networks
                .setBlocking(false)
                .setMetered(false) // inherit meteredness from the underlying networks
                .setUnderlyingNetworks(app.links.uplinks().map { it.network }.toTypedArray().ifEmpty { null })
                .setConfigureIntent(
                    PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                )
            for (pkg in excluded) {
                try {
                    b.addDisallowedApplication(pkg) // the app bypasses NetSteer entirely
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
            b.establish()
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
        app.usage.resetBaseline()
        Log.i(TAG, "vpn started, proxy port $port, excluded apps ${excluded.size}")

        jobs += scope.launch {
            // Keep the OS informed which networks carry VPN traffic (data accounting, metering).
            app.monitor.networks
                .map { app.links.uplinks().associate { it.id to it.network } }
                .distinctUntilChanged()
                .collect { links ->
                    Log.i(TAG, "uplinks: ${links.keys.sorted()}")
                    runCatching { setUnderlyingNetworks(links.values.toTypedArray().ifEmpty { null }) }
                }
        }
        jobs += scope.launch { ticker(px, scheduler, meter, boost) }
        jobs += scope.launch {
            // Networks Android hasn't validated (common for background mobile data on some
            // carriers) are checked by us, so a working link isn't left on standby.
            while (isActive) {
                app.links.probeUnvalidated()
                delay(if (power.isInteractive) 15_000 else 60_000)
            }
        }
        jobs += scope.launch {
            // Health: latency and loss per link (3 TCP connects each), used to hold failing links back.
            while (isActive) {
                val ls = app.links.links().filter { it.usable }
                app.health.retain(ls.map { it.id }.toSet())
                for (l in ls) {
                    val st = app.prober.sample(l.state)
                    app.health.record(l.id, st.rttMs, st.lossRatio)
                }
                delay(if (power.isInteractive) 30_000 else 120_000)
            }
        }
        jobs += scope.launch {
            // Filter lists: refresh at start (if stale) and check again every 6 hours.
            while (isActive) {
                maybeUpdateFilters(force = false)
                delay(6 * 3600_000L)
            }
        }
        jobs += scope.launch {
            // Excluding/including apps needs a new TUN interface.
            app.settings.values.map { it.excludedApps }.distinctUntilChanged().drop(1).collect {
                startService(Intent(this@CombineVpnService, CombineVpnService::class.java).setAction(ACTION_RELOAD))
            }
        }
    }

    private suspend fun ticker(px: ProxyServer, scheduler: LinkScheduler, meter: TrafficMeter, boost: BoostController) {
        var lastNotified = ""
        var lastNotifyAt = 0L
        var lastLogAt = 0L
        var lastSave = System.currentTimeMillis()
        while (true) {
            val interactive = power.isInteractive
            delay(if (interactive) 1_000 else 5_000)
            val now = System.currentTimeMillis()
            val rates = meter.sample(now)
            val s = app.settings.current
            val usable = app.links.uplinks().map { it.id }.toSet()
            val total = rates.values.sumOf { it.rxBps + it.txBps }

            val primaryUp = usable.any { !LinkRegistry.isMobile(it) }
            val d = boost.update(now, total, wifiUp = primaryUp, combine = s.combine, smart = s.smart)
            // The user's "use mobile data" switch overrides everything except "no Wi-Fi at all".
            mobileForNew = d.mobileForNewConnections && s.useMobile
            val requestMobile = d.requestMobile && s.useMobile
            app.monitor.setMobileRequested(requestMobile)
            val dual = manageDualWifi(s.combine && s.dualWifi)

            app.usage.record(rates.mapValues { it.value.rxTotal + it.value.txTotal })
            val taking = eligible(usable.toList()).toSet()
            val heldBack = (usable - taking).associateWith { id ->
                when {
                    app.health.unhealthy(id) -> "Held back: most health checks are failing"
                    LinkRegistry.isMobile(id) && !s.useMobile -> "Not used together with Wi-Fi (turned off in NetSteer); takes over if Wi-Fi fails"
                    LinkRegistry.isMobile(id) -> "Standby · joins for big downloads (battery saver)"
                    else -> "Standby"
                }
            }
            val idleReason = if (!s.useMobile) "Not used (turned off in NetSteer); takes over if Wi-Fi fails" else "Standby · joins for big downloads (battery saver)"
            val links = app.links.describe(taking, mobileHeld = requestMobile, heldBack = heldBack, mobileIdleReason = idleReason)
                .map { l ->
                    val r = rates[l.id]
                    val h = app.health.get(l.id)
                    l.copy(
                        rxBps = r?.rxBps ?: 0.0, txBps = r?.txBps ?: 0.0,
                        connections = scheduler.active(l.id), usedTodayBytes = app.usage.today(l.id),
                        rttMs = h?.rttMs, lossPct = h?.let { (it.lossRatio * 100).toInt() },
                    )
                }
            // Standby: links ready but not taking new connections (held back, or mobile that
            // Android brings up when Wi-Fi fails), plus added Wi-Fi networks Android can switch to.
            val current = currentWifiSsid()
            val addedStandby = app.wifiConnections.entries().count { it.ssid != current }
            val standby = links.count { it.state == LinkState.READY } + addedStandby
            val mode = ConnectionMode.classify(running = true, active = taking.size, standby = standby)

            val status = CombineStatus(
                running = true,
                links = links,
                totalBps = total,
                connections = px.activeConnections,
                combine = s.combine,
                smart = s.smart,
                boosting = d.boosting,
                mobileHeld = requestMobile,
                activeLinks = taking.size,
                mode = mode,
                standby = standby,
                adBlock = s.adBlock,
                blockedToday = app.filters.blockedToday.get(),
                dualWifi = dual,
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
                val ids = (rates.keys + listOf(WIFI, CELL)).distinct().sorted()
                // Machine-readable lines, used by the emulator end-to-end test.
                Log.i(TAG, "stats " + ids.joinToString(" ") { id ->
                    "$id=${rates[id]?.rxTotal ?: 0}/${rates[id]?.txTotal ?: 0}"
                } + " opened=" + ids.joinToString(",") { id -> "$id:${rates[id]?.opened ?: 0}" } +
                    " conns=${px.activeConnections} failed=${px.failedConnections}" +
                    " boost=${d.boosting} mobileReq=$requestMobile blocked=${app.filters.blockedTotal.get()}")
                Log.i(TAG, "links mode=${mode.name} " + links.joinToString("; ") { "${it.id}=${it.state}(rtt=${it.rttMs ?: "-"},loss=${it.lossPct ?: "-"})" })
            }
            if (now - lastSave >= 60_000) {
                app.filters.saveStats()
                app.usage.save()
                lastSave = now
            }
        }
    }

    /**
     * Android 13+ "multi-internet": phones with a dual-Wi-Fi chip can hold a second internet
     * Wi-Fi (other band or other router). Ask for it on the band the primary Wi-Fi isn't using.
     */
    private fun manageDualWifi(want: Boolean): DualWifi {
        val wifiMgr = getSystemService(WifiManager::class.java)
        val supported = Build.VERSION.SDK_INT >= 33 && wifiMgr != null && runCatching { wifiMgr.isStaConcurrencyForMultiInternetSupported }.getOrDefault(false)
        if (!supported) return DualWifi.UNSUPPORTED
        if (!want) {
            app.monitor.setSecondWifiRequested(false, 0)
            return DualWifi.OFF
        }
        val wifis = app.links.links().filter { it.id.startsWith("wifi") }
        if (wifis.size >= 2) return DualWifi.CONNECTED
        val primary = wifis.firstOrNull() ?: return DualWifi.SEARCHING
        if (!app.monitor.secondWifiRequested) {
            val freq = (primary.state.caps?.transportInfo as? WifiInfo)?.frequency ?: 0
            val band = if (freq >= 4900) ScanResult.WIFI_BAND_24_GHZ else ScanResult.WIFI_BAND_5_GHZ
            if (!app.monitor.setSecondWifiRequested(true, band)) return DualWifi.UNSUPPORTED
        }
        return DualWifi.SEARCHING
    }

    /** Download filter lists when stale, preferring an unmetered link so mobile data isn't spent on it. */
    fun maybeUpdateFilters(force: Boolean) {
        if (!app.settings.current.adBlock && !force) return
        if (!force && !app.filters.needsUpdate()) return
        val ups = app.links.uplinks()
        val unmetered = ups.firstOrNull { !LinkRegistry.isMobile(it.id) }?.network
        val network = unmetered ?: if (force || !app.filters.hasAnyDownloadedList()) ups.firstOrNull()?.network else null
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

    /** Stop the tunnel, proxy and background jobs, keeping the service and notification. */
    private fun teardown() {
        // Join the ticker so it cannot re-post the notification or status after we clear them.
        runBlocking { withTimeoutOrNull(2_000) { jobs.forEach { it.cancelAndJoin() } } }
        jobs.clear()
        if (nativeStarted) runCatching { TProxy.TProxyStopService() }
        nativeStarted = false
        runCatching { tun?.close() }
        tun = null
        proxy?.close()
        proxy = null
    }

    private fun stopVpn(message: String?) {
        val wasRunning = tun != null
        teardown()
        app.monitor.setMobileRequested(false)
        app.monitor.setSecondWifiRequested(false, 0)
        if (wasRunning) {
            app.filters.saveStats()
            app.usage.save()
        }
        _status.value = CombineStatus(running = false, message = message)
        if (wasRunning) Log.i(TAG, "vpn stopped: $message")
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(StatusNotifier.ID) }
        stopSelf()
    }

    /** Links offered to new connections: mobile is withheld unless combining/boosting wants it. */
    private fun uplinksForNewConnections(): List<AndroidUplink> {
        val all = app.links.uplinks()
        val ok = eligible(all.map { it.id }).toSet()
        return all.filter { it.id in ok }
    }

    /**
     * Which usable links may take NEW connections: mobile only if allowed (or nothing else works),
     * and links failing their health checks only if nothing healthy is left. Never returns empty
     * when [ids] isn't empty, so traffic is never stranded.
     */
    private fun eligible(ids: List<String>): List<String> {
        val noMobile = if (mobileForNew) ids else ids.filterNot(LinkRegistry::isMobile).ifEmpty { ids }
        return noMobile.filterNot { app.health.unhealthy(it) }.ifEmpty { noMobile }
    }

    /** SSID of the main Wi-Fi, if Android shares it with us (needs the location permission). */
    private fun currentWifiSsid(): String? = runCatching {
        @Suppress("DEPRECATION")
        getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" }
    }.getOrNull()

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
        const val ACTION_RELOAD = "com.intelram.netsteer.RELOAD"
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
