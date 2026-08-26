package com.threadprotection.app.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/** Result of trying to read the currently-connected Wi-Fi network's name. Android ties this
 *  specific reading to location permission on every supported version (not just newer ones) —
 *  it's real platform behaviour, not this app being extra cautious. */
sealed interface WifiStatus {
    data class Connected(val ssid: String) : WifiStatus
    data object NotConnected : WifiStatus
    data object PermissionNeeded : WifiStatus
    /** Permission is granted but the OS still won't hand back the SSID — almost always because
     *  device location (the system toggle, not just the app permission) is turned off. */
    data object LocationServicesOff : WifiStatus
}

object WifiInfo {
    fun current(context: Context): WifiStatus {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return WifiStatus.PermissionNeeded

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return WifiStatus.NotConnected
        val info = runCatching { wifiManager.connectionInfo }.getOrNull() ?: return WifiStatus.NotConnected
        val rawSsid = info.ssid ?: return WifiStatus.NotConnected

        return when {
            rawSsid == "<unknown ssid>" -> WifiStatus.LocationServicesOff
            rawSsid.isBlank() -> WifiStatus.NotConnected
            else -> WifiStatus.Connected(rawSsid.removeSurrounding("\""))
        }
    }
}
