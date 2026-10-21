package com.intelram.netsteer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import com.intelram.netsteer.core.WifiSecurity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Nearby Wi-Fi discovery through the public scan API (throttled by Android: 4 scans / 2 min in the foreground). */
class WifiScanner(private val ctx: Context) {
    data class Nearby(val ssid: String, val dBm: Int, val frequency: Int, val security: WifiSecurity)

    data class Outcome(val networks: List<Nearby>, val fresh: Boolean, val problem: String?)

    private val wifi = ctx.getSystemService(WifiManager::class.java)

    /** Permissions Android requires for scan results on this version (asked only when the user scans). */
    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun hasPermissions(): Boolean = requiredPermissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun locationOn(): Boolean = runCatching { ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull() ?: true

    suspend fun scan(): Outcome {
        val w = wifi ?: return Outcome(emptyList(), false, "This device has no Wi-Fi.")
        if (!hasPermissions()) return Outcome(emptyList(), false, "Allow location and nearby-device access to scan for Wi-Fi.")
        if (!w.isWifiEnabled) return Outcome(emptyList(), false, "Wi-Fi is turned off.")
        if (!locationOn()) return Outcome(cached(w), false, "Turn on Location in Android settings: Android only returns Wi-Fi scan results while it is on.")

        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                done.complete(i?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false)
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
        return try {
            @Suppress("DEPRECATION") // still the only public way to request a scan; Android throttles it
            val started = w.startScan()
            val updated = if (started) withTimeoutOrNull(12_000) { done.await() } ?: false else false
            val problem = if (!started || !updated) "Android limits how often apps can scan; showing the most recent results." else null
            Outcome(cached(w), updated, problem)
        } catch (e: SecurityException) {
            Outcome(emptyList(), false, "Permission denied: ${e.message}")
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    /** Strongest access point per network name; hidden networks (no name) are skipped. */
    private fun cached(w: WifiManager): List<Nearby> = try {
        w.scanResults.mapNotNull { r -> ssidOf(r)?.let { Nearby(it, r.level, r.frequency, WifiSecurity.parse(r.capabilities)) } }
            .groupBy { it.ssid to it.security }
            .map { (_, aps) -> aps.maxBy { it.dBm } }
            .sortedByDescending { it.dBm }
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun ssidOf(r: ScanResult): String? {
        val s = if (Build.VERSION.SDK_INT >= 33) {
            r.wifiSsid?.toString()?.removeSurrounding("\"")
        } else {
            @Suppress("DEPRECATION")
            r.SSID
        }
        return s?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }
}
