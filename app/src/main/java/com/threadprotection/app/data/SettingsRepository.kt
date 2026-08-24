package com.threadprotection.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.threadprotection.app.ui.theme.TpThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "thread_protection_prefs")

@Serializable
data class StoredAccount(val name: String, val email: String, val initial: String, val picture: String? = null)

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
        fun apiKey(id: ApiKeyId) = stringPreferencesKey(id.prefKey)
    }

    /** Whether real-time (background) protection is on — also read by `BootReceiver`. */
    val realtimeFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.REALTIME] ?: true }

    suspend fun setRealtime(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REALTIME] = enabled }
    }

    val themeFlow: Flow<TpThemeMode> = context.dataStore.data.map { prefs ->
        if (prefs[Keys.THEME] == "day") TpThemeMode.DAY else TpThemeMode.NIGHT
    }

    val accountFlow: Flow<Account?> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACCOUNT]?.let { raw ->
            runCatching { Json.decodeFromString<StoredAccount>(raw) }
                .getOrNull()
                ?.let { Account(it.name, it.email, it.initial, it.picture) }
        }
    }

    val apiKeysFlow: Flow<ApiKeys> = context.dataStore.data.map { prefs ->
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
        val raw = context.dataStore.data.map { it[Keys.CREDENTIAL] }.firstOrNull() ?: return false
        val parts = raw.split("::")
        if (parts.size != 3 || !parts[0].equals(email, ignoreCase = true)) return false
        return PasswordHasher.verify(password, parts[1], parts[2])
    }

    /** True once a local "Create an account" credential exists, regardless of which email. */
    val hasLocalCredentialFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.CREDENTIAL] != null }
}
