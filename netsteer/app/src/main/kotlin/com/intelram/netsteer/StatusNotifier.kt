package com.intelram.netsteer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import java.util.Locale

/**
 * The ongoing notification. Its small icon (shown in the status bar) is drawn
 * at runtime as the live combined speed, e.g. "24" over "Mb/s", so the user
 * can see at a glance that combining is active and how fast it is.
 */
class StatusNotifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)!!
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE // status-bar icons are tinted from alpha only
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        val ch = NotificationChannel(CHANNEL, "Combining status", NotificationManager.IMPORTANCE_DEFAULT).apply {
            // DEFAULT importance guarantees a status-bar icon; silence it explicitly.
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            description = "Live speed while Wi-Fi and mobile data are combined"
        }
        nm.createNotificationChannel(ch)
    }

    fun build(status: CombineStatus): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = if (status.running) speedIcon(status.totalBps) else Icon.createWithResource(ctx, R.drawable.ic_stat_combine)

        val title: String
        val text: String
        if (!status.running) {
            title = "NetSteer"
            text = status.message ?: "Starting…"
        } else {
            val up = status.links.filter { it.up }
            title = when (up.size) {
                0 -> "No network available"
                1 -> "${up[0].label} only · ${mbps(status.totalBps)} Mb/s"
                else -> "Combining ${up.joinToString(" + ") { it.label }} · ${mbps(status.totalBps)} Mb/s"
            }
            text = status.links.joinToString("   ") { l ->
                if (l.up) "${l.label} ↓${mbps(l.rxBps)} ↑${mbps(l.txBps)}" else "${l.label} off"
            }
        }
        val detail = if (status.running) "$text\n${status.connections} active connections" else text

        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_combine), "Stop", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    fun update(status: CombineStatus) = nm.notify(ID, build(status))

    private fun speedIcon(bps: Double): Icon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val value = mbps(bps)
        paint.textSize = when {
            value.length <= 2 -> 64f
            value.length == 3 -> 52f
            else -> 40f
        }
        c.drawText(value, size / 2f, 60f, paint)
        paint.textSize = 30f
        c.drawText("Mb/s", size / 2f, 92f, paint)
        return Icon.createWithBitmap(bmp)
    }

    companion object {
        const val CHANNEL = "combine"
        const val ID = 1

        /** Compact megabits-per-second: 0.4, 3.1, 24, 120. */
        fun mbps(bps: Double): String {
            val m = bps / 1_000_000
            return when {
                m < 0.05 -> "0"
                m < 10 -> String.format(Locale.US, "%.1f", m)
                else -> String.format(Locale.US, "%.0f", m)
            }
        }
    }
}
