package com.intelram.shield.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/** Device-wide network posture checks. */
class DeviceScanner(private val context: Context, private val feedbackStore: FindingFeedbackStore) {

    fun runChecks(): List<Finding> =
        listOfNotNull(networkAdvisoryFinding()).filterNot { feedbackStore.isDismissed(it.signature) }

    /** Advises using a VPN when connected to Wi-Fi without one active. Real, not simulated. */
    private fun networkAdvisoryFinding(): Finding? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null

        val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val onVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (!onWifi || onVpn) return null

        return Finding(
            severity = RiskLevel.LOW,
            category = FindingCategory.NETWORK,
            title = "No VPN active on this Wi-Fi network",
            description = "You're connected to Wi-Fi without a VPN running.",
            whyItMatters = "On networks you don't control — cafes, airports, hotels — traffic " +
                "can potentially be observed by others on the same network. A VPN encrypts it.",
            fixAction = FixAction.OpenSystemSettings(Settings.ACTION_VPN_SETTINGS),
        )
    }
}
