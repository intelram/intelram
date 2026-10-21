package com.intelram.netsteer

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: one tap to turn NetSteer on or off. */
class QuickTile : TileService() {
    private val ui = MainScope()
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = ui.launch { CombineVpnService.status.collect { render(it) } }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (CombineVpnService.status.value.running) {
            startService(Intent(this, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_STOP))
            return
        }
        val set = (application as NetSteerApp).settings.current
        if (VpnService.prepare(this) != null || (set.useMobile && !set.mobileConsent)) {
            openApp() // VPN consent / mobile-data cost consent must come from an activity
            return
        }
        try {
            startForegroundService(Intent(this, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_START))
        } catch (_: Exception) {
            // Background foreground-service start refused on this Android version: let the app do it.
            openApp()
        }
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setAction(MainActivity.ACTION_TILE_START)
            .putExtra(MainActivity.EXTRA_TOKEN, MainActivity.tileToken(this)) // other apps can't forge this
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }

    private fun render(s: CombineStatus) {
        val t = qsTile ?: return
        t.state = if (s.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "NetSteer"
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (s.running) "${StatusNotifier.mbps(s.totalBps)} Mb/s" else "Off"
        }
        t.updateTile()
    }
}
