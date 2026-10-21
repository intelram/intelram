package com.intelram.netsteer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User settings, persisted in SharedPreferences and observable so the running VPN applies changes live. */
class Settings(ctx: Context) {
    data class Values(
        /** Use all available networks together. */
        val combine: Boolean = true,
        /** Battery saver: bring mobile in only during sustained heavy traffic. Off = maximum speed. */
        val smart: Boolean = false,
        val adBlock: Boolean = true,
        /** Keep each site on one network (sites that tie sessions to one IP). */
        val compat: Boolean = false,
        val listAdGuard: Boolean = true,
        val listStevenBlack: Boolean = true,
        /** Domains the user never wants blocked. */
        val allowlist: Set<String> = emptySet(),
        /** Ask for a second Wi-Fi network on phones that support dual Wi-Fi. */
        val dualWifi: Boolean = true,
        /** Packages that bypass NetSteer entirely (e.g. banking apps that refuse VPNs). */
        val excludedApps: Set<String> = emptySet(),
        /** Use mobile data together with Wi-Fi. Off: mobile only when no Wi-Fi works (failover). */
        val useMobile: Boolean = true,
        /** The user acknowledged that mobile data use may cost money on their plan. */
        val mobileConsent: Boolean = false,
    )

    private val prefs = ctx.getSharedPreferences("netsteer", Context.MODE_PRIVATE)
    private val _values: MutableStateFlow<Values>
    val values: StateFlow<Values>
    val current: Values get() = _values.value

    init {
        migrate()
        _values = MutableStateFlow(load())
        values = _values.asStateFlow()
    }

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
            .putBoolean("dual_wifi", v.dualWifi)
            .putStringSet("excluded_apps", v.excludedApps)
            .putBoolean("use_mobile", v.useMobile)
            .putBoolean("mobile_consent", v.mobileConsent)
            .apply()
        _values.value = v
    }

    /**
     * v1 (0.3.0) defaulted to battery saver, which keeps mobile data on standby on
     * light traffic and looked like "mobile isn't working". v2 defaults to maximum speed.
     */
    private fun migrate() {
        val version = prefs.getInt("settings_version", 1)
        if (version < 2) prefs.edit().putBoolean("smart", false).putInt("settings_version", 2).apply()
    }

    private fun load() = Values(
        combine = prefs.getBoolean("combine", true),
        smart = prefs.getBoolean("smart", false),
        adBlock = prefs.getBoolean("adblock", true),
        compat = prefs.getBoolean("compat_mode", false),
        listAdGuard = prefs.getBoolean("list_adguard", true),
        listStevenBlack = prefs.getBoolean("list_stevenblack", true),
        allowlist = prefs.getStringSet("allowlist", emptySet())!!.toSet(),
        dualWifi = prefs.getBoolean("dual_wifi", true),
        excludedApps = prefs.getStringSet("excluded_apps", emptySet())!!.toSet(),
        useMobile = prefs.getBoolean("use_mobile", true),
        mobileConsent = prefs.getBoolean("mobile_consent", false),
    )
}
