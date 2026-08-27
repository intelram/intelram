package com.threadprotection.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.threadprotection.app.MainActivity
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
                if (manager.acceptChatRequest()) {
                    // Accepting from the shade must land the user *in* the conversation. Without
                    // this the chat opened correctly but stayed behind whatever they were doing,
                    // so from their side accepting appeared to do nothing at all.
                    val open = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(MainActivity.EXTRA_TARGET_SCREEN, MainActivity.TARGET_CHAT)
                    }
                    runCatching { context.startActivity(open) }
                        .onFailure { Log.w(TAG, "Couldn't bring the chat to the front", it) }
                } else {
                    // The request expired or the peer hung up between the notification appearing
                    // and this tap. Say so rather than silently doing nothing.
                    Log.w(TAG, "Accept tapped but the request was no longer open")
                }
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
