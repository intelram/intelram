package com.intelram.shield.scan

import android.content.Context

private const val PREFS_NAME = "threat_protection_feedback"
private const val KEY_DISMISSED = "dismissed_signatures"

/**
 * The app's one honest form of "self-learning": when a user tells it a
 * specific finding isn't a threat, it remembers that by a stable [signature]
 * (not the random per-scan finding id) and stops raising that exact finding
 * again on this device, from the next scan onward. There's no cloud model
 * and nothing is trained — it's a persisted preference the scan engine
 * consults, but the effect is real and immediate.
 */
class FindingFeedbackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDismissed(signature: String): Boolean =
        signature in prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

    fun dismiss(signature: String) {
        val updated = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toMutableSet()
        updated += signature
        prefs.edit().putStringSet(KEY_DISMISSED, updated).apply()
    }

    fun dismissedCount(): Int = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().size

    fun clearAll() {
        prefs.edit().remove(KEY_DISMISSED).apply()
    }
}
