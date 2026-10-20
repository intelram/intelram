package com.intelram.netsteer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User settings, persisted in SharedPreferences and observable so the running VPN applies changes live. */
class Settings(ctx: Context) {
    data class Values(
        /** Use Wi-Fi and mobile data together. */
        val combine: Boolean = true,
        /** Battery saver: bring mobile in only during sustained heavy traffic. */
        val smart: Boolean = true,
        val adBlock: Boolean = true,
        /** Keep each site on one network (sites that tie sessions to one IP). */
        val compat: Boolean = false,
        val listAdGuard: Boolean = true,
        val listStevenBlack: Boolean = true,
        /** Domains the user never wants blocked. */
        val allowlist: Set<String> = emptySet(),
    )

    private val prefs = ctx.getSharedPreferences("netsteer", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(load())
    val values: StateFlow<Values> = _values.asStateFlow()
    val current: Values get() = _values.value

    @Synchronized
    fun update(f: (Values) -> Values) {
        val v = f(_values.value)
        prefs.edit()
            .putBoolean("combine", v.combine)
            .putBoolean("smart", v.smart)
            .putBoolean("adblock", v.adBlock)
            .putBoolean("compat_mode", v.compat)
            .putBoolean("list_adguard", v.listAdGuard)
            .putBoolean("list_stevenblack", v.listStevenBlack)
            .putStringSet("allowlist", v.allowlist)
            .apply()
        _values.value = v
    }

    private fun load() = Values(
        combine = prefs.getBoolean("combine", true),
        smart = prefs.getBoolean("smart", true),
        adBlock = prefs.getBoolean("adblock", true),
        compat = prefs.getBoolean("compat_mode", false),
        listAdGuard = prefs.getBoolean("list_adguard", true),
        listStevenBlack = prefs.getBoolean("list_stevenblack", true),
        allowlist = prefs.getStringSet("allowlist", emptySet())!!.toSet(),
    )
}
