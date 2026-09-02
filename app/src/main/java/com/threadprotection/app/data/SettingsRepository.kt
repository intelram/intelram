package com.threadprotection.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.threadprotection.app.ui.theme.TpThemeMode
import com.threadprotection.app.ui.theme.systemThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "thread_protection_prefs")

@Serializable
data class StoredAccount(val name: String, val email: String, val initial: String, val picture: String? = null)

/** Plain-data mirror of `state.ProtectionSettings` — frequency stored as a string so this layer doesn't depend on the state package's enum. */
@Serializable
data class StoredProtectionSettings(
    val autoScan: Boolean = true,
    val breach: Boolean = true,
    val downloads: Boolean = true,
    val phishing: Boolean = true,
    val hardware: Boolean = true,
    val scanHour: Int = 3,
    val scanMinute: Int = 0,
    val scanFrequency: String = "DAILY",
    val scanDayOfWeek: Int = 2,
)

/** Plain-data mirror of `chat.ChatHistoryEntry` — this layer doesn't depend on the chat package.
 *  nodeId/publicKeyB64 default to "" for entries recorded before the mesh-relay feature existed;
 *  such an entry just can't be reached via relay (no long-term key on file for it) until you
 *  connect to it directly once more, which re-records it with both fields filled in. */
@Serializable
data class StoredChatHistoryEntry(
    val address: String,
    val name: String,
    val lastChattedAtMs: Long,
    val nodeId: String = "",
    val publicKeyB64: String = "",
)

/** How a conversation ended — recorded from what actually happened, not assumed. */
@Serializable
enum class StoredSessionStatus {
    /** Still open right now. Persisted anyway so nothing is lost if the process dies. */
    ACTIVE,

    /** The user ended it deliberately via Exit Chat. */
    COMPLETED,

    /** The peer went away, the socket dropped, or Bluetooth was turned off mid-conversation. */
    INTERRUPTED,
}

/** One message exactly as it was shown in the conversation — `delivered` is only ever true because
 *  the peer sent back a real ACK over the encrypted channel (see ChatWireMessage.Ack), never
 *  because the app optimistically assumed it arrived. */
@Serializable
data class StoredChatMessage(
    val id: String,
    val text: String,
    val fromMe: Boolean,
    val timestampMs: Long,
    val delivered: Boolean,
    val relayed: Boolean = false,
)

/** A full saved conversation. `sessionId` is minted once per connection and is the dedupe key, so
 *  repeated incremental saves of a live conversation update one entry rather than piling up. */
@Serializable
data class StoredChatSession(
    val sessionId: String,
    val address: String,
    val name: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val status: StoredSessionStatus,
    val messages: List<StoredChatMessage>,
)

/**
 * One threat the user marked resolved.
 *
 * [fingerprint] is what keeps the record honest across scans: it describes the *situation* the
 * finding reported (severity, risk score, type line), so a record only suppresses the finding
 * while that situation still holds. If the problem comes back — or gets worse — the fingerprint
 * no longer matches and the finding is shown again. See FindingIdentity.
 */
@Serializable
data class StoredResolvedFinding(
    val id: String,
    val fingerprint: String,
    val resolvedAtMs: Long,
    /** The finding's category, so a later scan can tell whether it was even in a position to
     *  conclude the problem is gone. Defaulted for records written before this field existed. */
    val category: String = "",
    /**
     * True once a scan that could actually see this category confirmed the problem was gone.
     *
     * A retired record stops suppressing the finding, but is deliberately kept: it is the memory
     * that lets the app say "this one is back" if the same problem returns, instead of presenting
     * a re-emergence as a first-time discovery.
     */
    val cleared: Boolean = false,
)

/**
 * One threat the user chose "Ignore for now" on.
 *
 * Mirrors [StoredResolvedFinding] exactly — same fingerprint-matching rules, same [cleared]
 * retirement once a scan confirms the underlying problem is gone. "Ignore for now" and "Fixed"
 * are different user intents (the UI still labels them differently), but they make the same
 * promise once made: the finding stays out of the way until the situation it describes actually
 * changes, across scans and app restarts alike.
 */
@Serializable
data class StoredIgnoredFinding(
    val id: String,
    val fingerprint: String,
    val ignoredAtMs: Long,
    val category: String = "",
    val cleared: Boolean = false,
)

/** This device's own long-term mesh identity — see `chat/MeshIdentity.kt`. nodeId is a random ID
 *  independent of the Bluetooth MAC (used for mesh routing); the keypair is ML-KEM-768, generated
 *  once and kept for the life of the install so contacts can address a message to this device
 *  without a live connection. */
@Serializable
data class StoredIdentity(val nodeId: String, val publicKeyB64: String, val privateKeyB64: String)

/** One message this device is carrying for the store-and-forward mesh relay — see
 *  `chat/MeshRelayManager.kt`. envelopeB64 is the complete encrypted wire envelope; msgId/
 *  destNodeId/ttl are pulled out alongside it purely so gossip and expiry don't need to re-parse
 *  it on every tick. */
@Serializable
data class StoredMeshEnvelope(
    val msgId: String,
    val destNodeId: String,
    val ttl: Int,
    val envelopeB64: String,
    val receivedAtMs: Long,
)

/** Which free threat-intel source a key belongs to — see README §Threat intelligence. */
enum class ApiKeyId(val prefKey: String, val label: String, val signupUrl: String) {
    SAFE_BROWSING("key_safe_browsing", "Google Safe Browsing", "https://console.cloud.google.com/"),
    VIRUS_TOTAL("key_virustotal", "VirusTotal", "https://www.virustotal.com/gui/join-us"),
    ABUSEIPDB("key_abuseipdb", "AbuseIPDB", "https://www.abuseipdb.com/register"),
    PHISHTANK("key_phishtank", "PhishTank", "https://phishtank.org/api_register.php"),
    URLHAUS("key_urlhaus", "URLhaus (abuse.ch)", "https://auth.abuse.ch/"),
    THREATFOX("key_threatfox", "ThreatFox (abuse.ch)", "https://auth.abuse.ch/"),
    NVD("key_nvd", "NVD (NIST CVE database)", "https://nvd.nist.gov/developers/request-an-api-key"),
}

data class ApiKeys(val values: Map<ApiKeyId, String> = emptyMap()) {
    operator fun get(id: ApiKeyId): String = values[id]?.trim().orEmpty()
    fun has(id: ApiKeyId): Boolean = get(id).isNotEmpty()
}

/**
 * Persists app preferences: the day/night theme choice and signed-in account (matching the
 * prototype's `localStorage` use — see README §State), local email/password credentials for the
 * "Create an account" path, and the user's own free API keys for the threat-intel sources listed
 * in README §Threat intelligence — none of these are bundled with the app; NVD works without a
 * key (just rate-limited), every other source needs the user's own free key pasted into Settings.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("tp_theme")
        val ACCOUNT = stringPreferencesKey("tp_google_account")
        val CREDENTIAL = stringPreferencesKey("tp_local_credential")
        val REALTIME = booleanPreferencesKey("tp_realtime")
        val PROTECTION_SETTINGS = stringPreferencesKey("tp_protection_settings")
        val CHAT_HISTORY = stringPreferencesKey("tp_chat_history")
        val CHAT_SESSIONS = stringPreferencesKey("tp_chat_sessions")
        val IDENTITY = stringPreferencesKey("tp_mesh_identity")
        val MESH_OUTBOX = stringPreferencesKey("tp_mesh_outbox")
        val RESOLVED_FINDINGS = stringPreferencesKey("tp_resolved_findings")
        val IGNORED_FINDINGS = stringPreferencesKey("tp_ignored_findings")
        fun apiKey(id: ApiKeyId) = stringPreferencesKey(id.prefKey)
    }

    /**
     * Every read goes through here rather than touching `context.dataStore.data` directly.
     *
     * DataStore signals a read failure — a corrupt or unreadable preferences file, a disk error —
     * by *throwing IOException into the flow*. That exception propagates straight out of whatever
     * `collect {}` is consuming it, and since those collects run in bare `viewModelScope.launch`
     * blocks with no handler, it reached the thread's default uncaught-exception handler and
     * terminated the process. Recovering to empty preferences here is the pattern DataStore's own
     * documentation prescribes: the app comes up with defaults instead of dying, and the real
     * exception is logged rather than swallowed silently. Anything that is *not* an IOException is
     * a genuine programming error and is deliberately rethrown.
     */
    private val prefs: Flow<Preferences> = context.dataStore.data.catch { e ->
        if (e is IOException) {
            Log.e(TAG, "DataStore read failed — recovering with empty preferences", e)
            emit(emptyPreferences())
        } else {
            throw e
        }
    }

    /** All 5 protection toggles plus the scheduled-scan time/frequency the user picked in Settings. */
    val protectionSettingsFlow: Flow<StoredProtectionSettings> = prefs.map { prefs ->
        prefs[Keys.PROTECTION_SETTINGS]?.let { raw ->
            runCatching { Json.decodeFromString<StoredProtectionSettings>(raw) }.getOrNull()
        } ?: StoredProtectionSettings()
    }

    suspend fun setProtectionSettings(settings: StoredProtectionSettings) {
        context.dataStore.edit { prefs -> prefs[Keys.PROTECTION_SETTINGS] = Json.encodeToString(settings) }
    }

    /** Chat "History" — devices you've successfully connected to before, newest first, capped at
     *  30 so it can't grow unbounded. Address is the dedupe key (a re-chat just moves it to the top
     *  and refreshes the name, in case the peer's Bluetooth name changed). */
    val chatHistoryFlow: Flow<List<StoredChatHistoryEntry>> = prefs.map { prefs ->
        prefs[Keys.CHAT_HISTORY]?.let { raw ->
            runCatching { Json.decodeFromString<List<StoredChatHistoryEntry>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun recordChatHistory(address: String, name: String, nodeId: String = "", publicKeyB64: String = "") {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CHAT_HISTORY]?.let { raw ->
                runCatching { Json.decodeFromString<List<StoredChatHistoryEntry>>(raw) }.getOrNull()
            } ?: emptyList()
            val updated = (listOf(StoredChatHistoryEntry(address, name, System.currentTimeMillis(), nodeId, publicKeyB64)) +
                current.filter { it.address != address }).take(30)
            prefs[Keys.CHAT_HISTORY] = Json.encodeToString(updated)
        }
    }

    /**
     * Full conversation transcripts, newest first, capped at [MAX_STORED_SESSIONS].
     *
     * Keyed by a `sessionId` minted once when a connection is established, so saving the same
     * session repeatedly (as messages arrive, on an unexpected disconnect, and again on a clean
     * Exit Chat) updates that one entry in place instead of creating a duplicate history item per
     * save. That incremental saving is also what preserves an interrupted conversation: the
     * messages received before the peer vanished are already on disk.
     */
    val chatSessionsFlow: Flow<List<StoredChatSession>> = prefs.map { prefs ->
        prefs[Keys.CHAT_SESSIONS]?.let { raw ->
            runCatching { Json.decodeFromString<List<StoredChatSession>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun saveChatSession(session: StoredChatSession) {
        // Don't persist an empty conversation — connecting and immediately leaving shouldn't leave
        // a blank entry cluttering History.
        if (session.messages.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CHAT_SESSIONS]?.let { raw ->
                runCatching { Json.decodeFromString<List<StoredChatSession>>(raw) }.getOrNull()
            } ?: emptyList()
            val updated = (listOf(session) + current.filter { it.sessionId != session.sessionId })
                .sortedByDescending { it.startedAtMs }
                .take(MAX_STORED_SESSIONS)
            prefs[Keys.CHAT_SESSIONS] = Json.encodeToString(updated)
        }
    }

    // ───────────────────────── resolved findings ─────────────────────────

    /**
     * Threats the user has marked resolved, kept across scans and app restarts.
     *
     * Stored as id + fingerprint rather than id alone — see [com.threadprotection.app.data.FindingIdentity].
     * The fingerprint is what makes "don't show it again unless it comes back" truthful: the record
     * only suppresses a finding while the situation it described still matches.
     */
    val resolvedFindingsFlow: Flow<List<StoredResolvedFinding>> = prefs.map { prefs ->
        prefs[Keys.RESOLVED_FINDINGS]?.let { raw ->
            runCatching { Json.decodeFromString<List<StoredResolvedFinding>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    /** Records one finding as resolved. Re-resolving the same id replaces its record, so a finding
     *  that came back and was dealt with again is stored against its *current* fingerprint. */
    suspend fun markFindingResolved(id: String, fingerprint: String, category: String) {
        context.dataStore.edit { prefs ->
            val current = readResolved(prefs)
            val record = StoredResolvedFinding(id, fingerprint, System.currentTimeMillis(), category, cleared = false)
            val updated = (listOf(record) + current.filter { it.id != id })
                .sortedByDescending { it.resolvedAtMs }
                .take(MAX_RESOLVED_FINDINGS)
            prefs[Keys.RESOLVED_FINDINGS] = Json.encodeToString(updated)
        }
    }

    /**
     * Retires records whose problem a scan has confirmed gone. They stop suppressing the finding but
     * stay on disk, so if the same problem returns the app can report it as a re-emergence rather
     * than a brand-new discovery the user has never seen.
     */
    suspend fun markFindingsCleared(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val updated = readResolved(prefs).map {
                if (it.id in ids) it.copy(cleared = true) else it
            }
            prefs[Keys.RESOLVED_FINDINGS] = Json.encodeToString(updated)
        }
    }

    /** Drops resolution records — used when a finding reappears with a different fingerprint, and
     *  when the user deliberately un-resolves one. */
    suspend fun clearResolvedFindings(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val remaining = readResolved(prefs).filter { it.id !in ids }
            prefs[Keys.RESOLVED_FINDINGS] = Json.encodeToString(remaining)
        }
    }

    suspend fun clearAllResolvedFindings() {
        context.dataStore.edit { prefs -> prefs.remove(Keys.RESOLVED_FINDINGS) }
    }

    private fun readResolved(prefs: Preferences): List<StoredResolvedFinding> =
        prefs[Keys.RESOLVED_FINDINGS]?.let { raw ->
            runCatching { Json.decodeFromString<List<StoredResolvedFinding>>(raw) }.getOrNull()
        } ?: emptyList()

    // ───────────────────────── ignored findings ─────────────────────────

    /**
     * Threats the user chose "Ignore for now" on, kept across scans and app restarts.
     *
     * Mirrors [resolvedFindingsFlow] exactly — see [StoredIgnoredFinding] for why "Ignore for now"
     * makes the same durability promise as "Fixed" despite being a different user intent.
     */
    val ignoredFindingsFlow: Flow<List<StoredIgnoredFinding>> = prefs.map { prefs ->
        prefs[Keys.IGNORED_FINDINGS]?.let { raw ->
            runCatching { Json.decodeFromString<List<StoredIgnoredFinding>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    /** Records one finding as ignored. Re-ignoring the same id (e.g. after it re-emerged in a
     *  different shape) replaces its record against the current fingerprint. */
    suspend fun markFindingIgnored(id: String, fingerprint: String, category: String) {
        context.dataStore.edit { prefs ->
            val current = readIgnored(prefs)
            val record = StoredIgnoredFinding(id, fingerprint, System.currentTimeMillis(), category, cleared = false)
            val updated = (listOf(record) + current.filter { it.id != id })
                .sortedByDescending { it.ignoredAtMs }
                .take(MAX_IGNORED_FINDINGS)
            prefs[Keys.IGNORED_FINDINGS] = Json.encodeToString(updated)
        }
    }

    /** Retires ignored records whose problem a scan has confirmed gone — mirrors [markFindingsCleared]. */
    suspend fun markIgnoredCleared(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val updated = readIgnored(prefs).map {
                if (it.id in ids) it.copy(cleared = true) else it
            }
            prefs[Keys.IGNORED_FINDINGS] = Json.encodeToString(updated)
        }
    }

    /** Drops ignore records — used when a finding reappears with a different fingerprint, and when
     *  the user deliberately un-ignores one. */
    suspend fun clearIgnoredFindings(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val remaining = readIgnored(prefs).filter { it.id !in ids }
            prefs[Keys.IGNORED_FINDINGS] = Json.encodeToString(remaining)
        }
    }

    private fun readIgnored(prefs: Preferences): List<StoredIgnoredFinding> =
        prefs[Keys.IGNORED_FINDINGS]?.let { raw ->
            runCatching { Json.decodeFromString<List<StoredIgnoredFinding>>(raw) }.getOrNull()
        } ?: emptyList()

    suspend fun deleteChatSession(sessionId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CHAT_SESSIONS]?.let { raw ->
                runCatching { Json.decodeFromString<List<StoredChatSession>>(raw) }.getOrNull()
            } ?: emptyList()
            prefs[Keys.CHAT_SESSIONS] = Json.encodeToString(current.filter { it.sessionId != sessionId })
        }
    }

    suspend fun clearChatSessions() {
        context.dataStore.edit { prefs -> prefs[Keys.CHAT_SESSIONS] = Json.encodeToString(emptyList<StoredChatSession>()) }
    }

    // ── Mesh relay: long-term identity + store-and-forward outbox (chat/MeshRelayManager.kt) ──

    /** Returns the existing identity if one's already stored, otherwise generates one with
     *  [generate] and persists it — the whole read-generate-write happens inside DataStore's own
     *  transactional `edit`, so two callers racing on first launch can't create two identities. */
    suspend fun ensureIdentity(generate: () -> StoredIdentity): StoredIdentity {
        var result: StoredIdentity? = null
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.IDENTITY]?.let { raw -> runCatching { Json.decodeFromString<StoredIdentity>(raw) }.getOrNull() }
            result = if (existing != null) {
                existing
            } else {
                val fresh = generate()
                prefs[Keys.IDENTITY] = Json.encodeToString(fresh)
                fresh
            }
        }
        return result!!
    }

    /** Everything this device is currently carrying to relay onward or deliver locally, oldest
     *  first. Not exposed as a live Flow — MeshRelayManager only needs a snapshot per gossip tick. */
    suspend fun meshOutboxOnce(): List<StoredMeshEnvelope> =
        prefs.map { prefs ->
            prefs[Keys.MESH_OUTBOX]?.let { raw -> runCatching { Json.decodeFromString<List<StoredMeshEnvelope>>(raw) }.getOrNull() } ?: emptyList()
        }.firstOrNull() ?: emptyList()

    /** Merges newly-seen envelopes into the outbox (deduped by msgId), drops anything past
     *  [maxAgeMs], and caps the total at [cap] — oldest evicted first — so the store can't grow
     *  unbounded while messages wait for a carrier. */
    suspend fun mergeMeshEnvelopes(newEntries: List<StoredMeshEnvelope>, maxAgeMs: Long = 24 * 60 * 60 * 1000L, cap: Int = 60) {
        if (newEntries.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.MESH_OUTBOX]?.let { raw ->
                runCatching { Json.decodeFromString<List<StoredMeshEnvelope>>(raw) }.getOrNull()
            } ?: emptyList()
            val now = System.currentTimeMillis()
            val byId = LinkedHashMap<String, StoredMeshEnvelope>()
            (current + newEntries).forEach { byId[it.msgId] = it }
            val fresh = byId.values
                .filter { now - it.receivedAtMs <= maxAgeMs }
                .sortedByDescending { it.receivedAtMs }
                .take(cap)
            prefs[Keys.MESH_OUTBOX] = Json.encodeToString(fresh)
        }
    }

    /** Evicts one envelope — call once it's been decrypted and delivered locally, or the caller
     *  has otherwise fully handled it. */
    suspend fun removeMeshEnvelope(msgId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.MESH_OUTBOX]?.let { raw ->
                runCatching { Json.decodeFromString<List<StoredMeshEnvelope>>(raw) }.getOrNull()
            } ?: return@edit
            prefs[Keys.MESH_OUTBOX] = Json.encodeToString(current.filter { it.msgId != msgId })
        }
    }

    /** Whether real-time (background) protection is on — also read by `BootReceiver`. */
    val realtimeFlow: Flow<Boolean> = prefs.map { it[Keys.REALTIME] ?: true }

    suspend fun setRealtime(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REALTIME] = enabled }
    }

    /** Falls back to the device's own dark/light setting until the user picks a theme in
     *  Settings — a fresh install should match the phone, not always open in Night mode. */
    val themeFlow: Flow<TpThemeMode> = prefs.map { prefs ->
        when (prefs[Keys.THEME]) {
            "day" -> TpThemeMode.DAY
            "night" -> TpThemeMode.NIGHT
            else -> systemThemeMode(context)
        }
    }

    val accountFlow: Flow<Account?> = prefs.map { prefs ->
        prefs[Keys.ACCOUNT]?.let { raw ->
            runCatching { Json.decodeFromString<StoredAccount>(raw) }
                .getOrNull()
                ?.let { Account(it.name, it.email, it.initial, it.picture) }
        }
    }

    val apiKeysFlow: Flow<ApiKeys> = prefs.map { prefs ->
        ApiKeys(ApiKeyId.entries.associateWith { prefs[Keys.apiKey(it)].orEmpty() }.filterValues { it.isNotEmpty() })
    }

    suspend fun setTheme(mode: TpThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = if (mode == TpThemeMode.DAY) "day" else "night" }
    }

    suspend fun setAccount(account: Account?) {
        context.dataStore.edit { prefs ->
            if (account == null) {
                prefs.remove(Keys.ACCOUNT)
            } else {
                val stored = StoredAccount(account.name, account.email, account.initial, account.picture)
                prefs[Keys.ACCOUNT] = Json.encodeToString(stored)
            }
        }
    }

    suspend fun setApiKey(id: ApiKeyId, value: String) {
        context.dataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(Keys.apiKey(id)) else prefs[Keys.apiKey(id)] = value.trim()
        }
    }

    // ── Local "Create an account" credentials (on-device only, PBKDF2-hashed) ──

    suspend fun setLocalCredential(email: String, password: String) {
        val stored = PasswordHasher.hash(password)
        context.dataStore.edit { prefs -> prefs[Keys.CREDENTIAL] = "$email::${stored.saltB64}::${stored.hashB64}" }
    }

    suspend fun verifyLocalCredential(email: String, password: String): Boolean {
        val raw = prefs.map { it[Keys.CREDENTIAL] }.firstOrNull() ?: return false
        val parts = raw.split("::")
        if (parts.size != 3 || !parts[0].equals(email, ignoreCase = true)) return false
        return PasswordHasher.verify(password, parts[1], parts[2])
    }

    /** True once a local "Create an account" credential exists, regardless of which email. */
    val hasLocalCredentialFlow: Flow<Boolean> = prefs.map { it[Keys.CREDENTIAL] != null }

    private companion object {
        const val TAG = "TPStore"

        /** Transcripts are small, but DataStore rewrites the whole file on every edit, so this
         *  stays bounded rather than growing without limit across the life of the install. */
        const val MAX_STORED_SESSIONS = 50

        /** Plenty for a phone's worth of findings, and bounded so the record can't grow forever
         *  as apps come and go over months of scans. */
        const val MAX_RESOLVED_FINDINGS = 300

        /** Mirrors [MAX_RESOLVED_FINDINGS] for the ignored-findings list. */
        const val MAX_IGNORED_FINDINGS = 300
    }
}
