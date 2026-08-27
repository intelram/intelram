package com.threadprotection.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.threadprotection.app.ThreadProtectionApp
import com.threadprotection.app.chat.BluetoothChatManager

/**
 * Handles the Accept / Deny buttons on the incoming-chat-request notification.
 *
 * A BroadcastReceiver has no ViewModel and no UI, which is exactly why BluetoothChatManager had to
 * become a process singleton: this drives the same live connection the Chat screen does, so
 * answering from the notification shade is identical to answering in the app.
 *
 * Not exported — only this app's own PendingIntents can trigger it, so another app can't accept a
 * chat on the user's behalf.
 */
class ChatRequestActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ThreadProtectionApp ?: return
        val manager = BluetoothChatManager.getInstance(app, app.settingsRepository)
        when (intent.action) {
            ACTION_ACCEPT -> {
                Log.i(TAG, "Accept tapped on the chat-request notification")
                manager.acceptChatRequest()
            }
            ACTION_DENY -> {
                Log.i(TAG, "Deny tapped on the chat-request notification")
                manager.denyChatRequest()
            }
            else -> return
        }
        NotificationHelper.cancelChatRequest(context)
    }

    companion object {
        private const val TAG = "TPChat"
        const val ACTION_ACCEPT = "com.threadprotection.app.action.CHAT_ACCEPT"
        const val ACTION_DENY = "com.threadprotection.app.action.CHAT_DENY"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
