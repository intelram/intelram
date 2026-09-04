package com.threadprotection.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.threadprotection.app.MainActivity
import com.threadprotection.app.ThreadProtectionApp
import com.threadprotection.app.chat.BluetoothChatManager

/**
 * Handles the Answer / Decline buttons on the incoming-call notification — mirrors
 * [ChatRequestActionReceiver] exactly, and for the same reason: this drives the same live
 * `BluetoothChatManager` singleton the in-app incoming-call overlay does, so answering from the
 * notification shade is identical to answering in the app.
 *
 * Not exported — only this app's own PendingIntents can trigger it.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ThreadProtectionApp ?: return
        val manager = BluetoothChatManager.getInstance(app, app.settingsRepository)
        when (intent.action) {
            ACTION_ANSWER -> {
                Log.i(TAG, "Answer tapped on the incoming-call notification")
                if (manager.acceptCall()) {
                    // Answering from the shade must land the user *in* the call, same as accepting
                    // a chat request has to bring the conversation to the front.
                    val open = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(MainActivity.EXTRA_TARGET_SCREEN, MainActivity.TARGET_CHAT)
                    }
                    runCatching { context.startActivity(open) }
                        .onFailure { Log.w(TAG, "Couldn't bring the call to the front", it) }
                } else {
                    Log.w(TAG, "Answer tapped but the call was no longer ringing")
                }
            }
            ACTION_DECLINE -> {
                Log.i(TAG, "Decline tapped on the incoming-call notification")
                manager.declineCall()
            }
            else -> return
        }
        NotificationHelper.cancelIncomingCall(context)
    }

    companion object {
        private const val TAG = "TPChat"
        const val ACTION_ANSWER = "com.threadprotection.app.action.CALL_ANSWER"
        const val ACTION_DECLINE = "com.threadprotection.app.action.CALL_DECLINE"
        const val EXTRA_CALL_ID = "call_id"
    }
}
