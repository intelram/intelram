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
    private const val NOTIF_ID_COMING_SOON = 9001
    private const val NOTIF_ID_CHAT_REQUEST = 9100
    private const val NOTIF_ID_INCOMING_CALL = 9101
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

    /**
     * A real heads-up notification with working Accept and Deny buttons, posted when someone
     * nearby asks to chat and the user isn't already looking at the Chat screen.
     *
     * This is the fix for "the recipient never sees anything": the in-app request card only exists
     * while Chat is open, so a request arriving while the user was on any other screen — or with
     * the app in the background — was invisible and simply timed out. The buttons broadcast to
     * [ChatRequestActionReceiver], which drives the same shared BluetoothChatManager the UI does,
     * so answering from the notification is exactly answering in the app.
     */
    fun postChatRequest(context: Context, displayName: String, requestId: String) {
        ensureChannels(context)

        fun action(name: String) = PendingIntent.getBroadcast(
            context,
            (requestId + name).hashCode(),
            Intent(context, ChatRequestActionReceiver::class.java).apply {
                this.action = name
                setPackage(context.packageName)
                putExtra(ChatRequestActionReceiver.EXTRA_REQUEST_ID, requestId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openChat = PendingIntent.getActivity(
            context, requestId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SCREEN
                putExtra(MainActivity.EXTRA_TARGET_SCREEN, MainActivity.TARGET_CHAT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tile_permissions)
            .setContentTitle("$displayName wants to chat")
            .setContentText("Encrypted Bluetooth chat request from a device nearby.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$displayName wants to start an encrypted Bluetooth chat with you. Accept to begin, or deny to decline."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            // Stays put until answered — a chat request the user swipes past by accident would
            // otherwise silently time out with no way back to it.
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(openChat)
            .addAction(0, "Accept", action(ChatRequestActionReceiver.ACTION_ACCEPT))
            .addAction(0, "Deny", action(ChatRequestActionReceiver.ACTION_DENY))
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIF_ID_CHAT_REQUEST, notification)
    }

    /** Clears the request notification once it's answered, timed out, or the peer went away. */
    fun cancelChatRequest(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID_CHAT_REQUEST)
    }

    /**
     * A real heads-up notification with working Answer/Decline buttons for an incoming voice call
     * — mirrors [postChatRequest] exactly, and for the same reason: `BluetoothChatManager` is a
     * process singleton that keeps ringing even with the app fully closed (§7.17 in the engineering
     * map), so the notification has to be posted from there directly, not from a ViewModel
     * collector that might not be alive to see the event.
     */
    fun postIncomingCall(context: Context, callerName: String, callId: String) {
        ensureChannels(context)

        fun action(name: String) = PendingIntent.getBroadcast(
            context,
            (callId + name).hashCode(),
            Intent(context, CallActionReceiver::class.java).apply {
                this.action = name
                setPackage(context.packageName)
                putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openChat = PendingIntent.getActivity(
            context, callId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SCREEN
                putExtra(MainActivity.EXTRA_TARGET_SCREEN, MainActivity.TARGET_CHAT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tile_permissions)
            .setContentTitle("Incoming call from $callerName")
            .setContentText("Encrypted Bluetooth voice call.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(openChat)
            .addAction(0, "Answer", action(CallActionReceiver.ACTION_ANSWER))
            .addAction(0, "Decline", action(CallActionReceiver.ACTION_DECLINE))
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIF_ID_INCOMING_CALL, notification)
    }

    /** Clears the incoming-call notification once it's answered, declined, ended, or timed out. */
    fun cancelIncomingCall(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID_INCOMING_CALL)
    }

    fun postComingSoon(context: Context, title: String, text: String, targetScreen: String) {
        ensureChannels(context)
        val openApp = PendingIntent.getActivity(
            context, NOTIF_ID_COMING_SOON,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SCREEN
                putExtra(MainActivity.EXTRA_TARGET_SCREEN, targetScreen)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
        manager.notify(NOTIF_ID_COMING_SOON, notification)
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
