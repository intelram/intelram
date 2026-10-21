package com.intelram.netsteer

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import com.intelram.netsteer.core.WifiSecurity
import com.intelram.netsteer.core.WifiSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The user's added Wi-Fi sources (max 4), connected through Android's
 * network-suggestion API: the user supplies the password and approves NetSteer's
 * suggestions; Android itself decides when to join a suggested network.
 *
 * What this can and cannot do on a standard phone:
 *  - Android keeps ONE internet Wi-Fi (two on Android 13+ "multi-internet" hardware).
 *    Added networks beyond that are standby/failover sources Android switches to.
 *  - An app cannot force Android to switch its main Wi-Fi; "Switch now" opens the
 *    system Wi-Fi panel for the user.
 *  - Passwords are passed to Android and not kept by NetSteer (kept in memory only
 *    for a quick Retry in this session).
 */
class WifiConnectionManager(private val ctx: Context) {
    enum class Status(val label: String) {
        CONNECTED("Connected · internet verified"),
        CONNECTED_NO_INTERNET("Connected · no internet"),
        STANDBY("Saved · standby (Android joins it when it's the best or only option)"),
        NOT_IN_RANGE("Saved · not in range"),
        WRONG_PASSWORD("Wrong password"),
        FAILED("Could not connect"),
        DISCONNECTED("Disconnected by you"),
        NEEDS_PASSWORD("Password needed"),
    }

    data class Entry(val ssid: String, val security: WifiSecurity, val status: Status, val detail: String = "")

    enum class Result { OK, FULL, DUPLICATE, UNSUPPORTED, INVALID, BAD_PASSWORD, NOT_ALLOWED, ERROR }

    private val wifi = ctx.getSystemService(WifiManager::class.java)
    private val prefs = ctx.getSharedPreferences("netsteer_bonding", Context.MODE_PRIVATE)
    private val selection = WifiSelection.deserialize(prefs.getString("selected", null))
    private val disconnected = HashSet(prefs.getStringSet("disconnected", emptySet())!!)
    private val passwords = ConcurrentHashMap<String, String>() // session only, for Retry
    private val failures = ConcurrentHashMap<String, Int>()
    private val _changes = MutableStateFlow(0)
    /** Bumps whenever the list or a connection outcome changes. */
    val changes: StateFlow<Int> = _changes.asStateFlow()
    private var listening = false

    val count: Int get() = selection.size

    fun entries(): List<WifiSelection.Entry> = selection.list

    /** Add a network chosen by the user and suggest it to Android. */
    @Synchronized
    fun add(ssid: String, security: WifiSecurity, password: String?): Result {
        when (selection.add(ssid, security)) {
            WifiSelection.AddResult.FULL -> return Result.FULL
            WifiSelection.AddResult.DUPLICATE -> return Result.DUPLICATE
            WifiSelection.AddResult.UNSUPPORTED -> return Result.UNSUPPORTED
            WifiSelection.AddResult.INVALID_SSID -> return Result.INVALID
            WifiSelection.AddResult.ADDED -> Unit
        }
        val r = suggest(ssid.trim(), security, password)
        if (r != Result.OK) selection.remove(ssid.trim(), security) else persist()
        bump()
        return r
    }

    /** (Re)suggest a saved entry, e.g. after Disconnect or a wrong password. */
    @Synchronized
    fun connect(ssid: String, security: WifiSecurity, password: String? = null): Result {
        disconnected -= key(ssid, security)
        failures.remove(ssid)
        val r = suggest(ssid, security, password ?: passwords[key(ssid, security)])
        persist()
        bump()
        return r
    }

    /** Withdraw the suggestion: Android disconnects from it if it is the current network. */
    @Synchronized
    fun disconnect(ssid: String, security: WifiSecurity) {
        unsuggest(ssid, security)
        disconnected += key(ssid, security)
        persist()
        bump()
    }

    @Synchronized
    fun remove(ssid: String, security: WifiSecurity) {
        unsuggest(ssid, security)
        selection.remove(ssid, security)
        disconnected -= key(ssid, security)
        passwords.remove(key(ssid, security))
        failures.remove(ssid)
        persist()
        bump()
    }

    /** Status of every added network against what Android is doing right now. */
    fun statuses(currentSsid: String?, currentUsable: Boolean, inRange: Set<String>, rangeKnown: Boolean): List<Entry> {
        val ours = suggestedSsids()
        return selection.list.map { e ->
            val k = key(e.ssid, e.security)
            val status = when {
                k in disconnected -> Status.DISCONNECTED
                e.ssid == currentSsid -> if (currentUsable) Status.CONNECTED else Status.CONNECTED_NO_INTERNET
                failures[e.ssid] == WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_AUTHENTICATION -> Status.WRONG_PASSWORD
                failures[e.ssid] != null -> Status.FAILED
                ours != null && e.ssid !in ours -> if (e.security.needsPassword) Status.NEEDS_PASSWORD else Status.FAILED
                rangeKnown && e.ssid !in inRange -> Status.NOT_IN_RANGE
                else -> Status.STANDBY
            }
            Entry(e.ssid, e.security, status)
        }
    }

    /**
     * Register for Android's per-suggestion failure reports (wrong password, association, DHCP).
     * Needs the location permission; harmless to call repeatedly.
     */
    fun listenForFailures() {
        val w = wifi ?: return
        if (listening || Build.VERSION.SDK_INT < 30) return
        try {
            w.addSuggestionConnectionStatusListener(ctx.mainExecutor) { s, code ->
                val ssid = s.ssid?.removeSurrounding("\"") ?: return@addSuggestionConnectionStatusListener
                failures[ssid] = code
                Log.i(TAG, "bondwifi failure ssid=$ssid code=$code")
                bump()
            }
            listening = true
        } catch (e: SecurityException) {
            Log.i(TAG, "suggestion status listener needs location permission")
        }
    }

    private fun suggest(ssid: String, security: WifiSecurity, password: String?): Result {
        val w = wifi ?: return Result.ERROR
        if (!security.acceptsPassword(password)) return Result.BAD_PASSWORD
        val s = try {
            build(ssid, security, password)
        } catch (e: IllegalArgumentException) {
            return Result.BAD_PASSWORD // e.g. WPA2 passphrase not 8-63 characters
        }
        unsuggest(ssid, security) // replace any older suggestion (e.g. with a wrong password)
        val code = try {
            w.addNetworkSuggestions(listOf(s))
        } catch (e: SecurityException) {
            return Result.NOT_ALLOWED
        }
        Log.i(TAG, "bondwifi suggest ssid=$ssid code=$code")
        return when (code) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> {
                if (password != null) passwords[key(ssid, security)] = password
                failures.remove(ssid)
                Result.OK
            }
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> Result.NOT_ALLOWED
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP -> Result.FULL
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID -> if (security.needsPassword) Result.BAD_PASSWORD else Result.INVALID
            else -> Result.ERROR
        }
    }

    private fun unsuggest(ssid: String, security: WifiSecurity) {
        val w = wifi ?: return
        val existing = currentSuggestions()?.filter { it.ssid?.removeSurrounding("\"") == ssid }
            ?: runCatching { listOf(build(ssid, security, passwords[key(ssid, security)] ?: PLACEHOLDER)) }.getOrDefault(emptyList())
        if (existing.isEmpty()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) w.removeNetworkSuggestions(existing, WifiManager.ACTION_REMOVE_SUGGESTION_DISCONNECT)
            else w.removeNetworkSuggestions(existing)
        }
    }

    private fun build(ssid: String, security: WifiSecurity, password: String?): WifiNetworkSuggestion {
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
        when (security) {
            WifiSecurity.OPEN -> Unit
            WifiSecurity.OWE -> b.setIsEnhancedOpen(true)
            WifiSecurity.WPA2_PSK, WifiSecurity.WPA2_WPA3 -> b.setWpa2Passphrase(password!!)
            WifiSecurity.WPA3_SAE -> b.setWpa3Passphrase(password!!)
            else -> throw IllegalArgumentException("unsupported security $security")
        }
        return b.build()
    }

    private fun currentSuggestions(): List<WifiNetworkSuggestion>? =
        if (Build.VERSION.SDK_INT >= 30) runCatching { wifi?.networkSuggestions }.getOrNull() else null

    /** SSIDs Android currently holds as NetSteer suggestions (null if this Android can't tell us). */
    private fun suggestedSsids(): Set<String>? = currentSuggestions()?.mapNotNull { it.ssid?.removeSurrounding("\"") }?.toSet()

    private fun persist() {
        prefs.edit().putString("selected", selection.serialize()).putStringSet("disconnected", HashSet(disconnected)).apply()
    }

    private fun bump() { _changes.value = _changes.value + 1 }

    private fun key(ssid: String, security: WifiSecurity) = "${security.name}\t$ssid"

    private companion object {
        const val TAG = "NetSteer"
        const val PLACEHOLDER = "placeholder-password" // only used to build a removal key when Android can't list suggestions
    }
}
