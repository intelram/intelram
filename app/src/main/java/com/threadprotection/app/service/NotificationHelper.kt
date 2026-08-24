package com.threadprotection.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.threadprotection.app.MainActivity
import com.threadprotection.app.R

object NotificationHelper {
    const val CHANNEL_PERSISTENT = "protection_status"
    const val CHANNEL_ALERTS = "security_alerts"
    const val CHANNEL_REMINDERS = "security_reminders"

    const val NOTIF_ID_PERSISTENT = 1001
    private var nextAlertId = 2000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PERSISTENT, "Protection status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows that real-time protection is running"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Security alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Suspicious hardware, risky permissions and other real-time findings"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Security reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Occasional reminders — e.g. turning on two-step verification"
            },
        )
    }

    fun persistentNotification(context: Context): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_tile_permissions)
            .setContentTitle("Thread Protection is active")
            .setContentText("Watching USB, Bluetooth and hardware changes in the background")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    fun postAlert(context: Context, title: String, text: String, targetScreen: String) {
        ensureChannels(context)
        val openApp = PendingIntent.getActivity(
            context, targetScreen.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SCREEN
                putExtra(MainActivity.EXTRA_TARGET_SCREEN, targetScreen)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tile_permissions)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(nextAlertId++, notification)
    }

    fun postReminder(context: Context, title: String, text: String) {
        ensureChannels(context)
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SCREEN
                putExtra(MainActivity.EXTRA_TARGET_SCREEN, MainActivity.TARGET_SETTINGS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_tile_permissions)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIF_ID_PERSISTENT + 500, notification)
    }
}
