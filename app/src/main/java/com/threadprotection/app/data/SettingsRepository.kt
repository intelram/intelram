package com.threadprotection.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.content.Context
import com.threadprotection.app.ui.theme.TpThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "thread_protection_prefs")

@Serializable
data class StoredAccount(val name: String, val email: String, val initial: String, val picture: String? = null)

/**
 * Persists the two things the prototype keeps in `localStorage`: the day/night theme choice
 * (`tp_theme`) and the signed-in account (`tp_google_account`). See README §State.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("tp_theme")
        val ACCOUNT = stringPreferencesKey("tp_google_account")
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
}
