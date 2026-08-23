package com.intelram.shield.scan

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Device-wide security posture checks (root, dev settings, screen lock, network, etc). */
class DeviceScanner(private val context: Context) {

    fun runChecks(): List<Finding> {
        val findings = mutableListOf<Finding>()
        val securitySettings = FixAction.OpenSystemSettings(Settings.ACTION_SECURITY_SETTINGS)

        if (isProbablyRooted()) {
            findings += Finding(
                severity = RiskLevel.HIGH,
                category = FindingCategory.SYSTEM,
                title = "Device may be rooted",
                description = "Root indicators were found (su binary, a root-management app, " +
                    "or a test-keys build).",
                whyItMatters = "Rooting removes Android's app-sandbox protections, so a " +
                    "malicious app can do far more damage than it normally could.",
            )
        }

        if (isAdbEnabled()) {
            findings += Finding(
                severity = RiskLevel.MEDIUM,
                category = FindingCategory.SYSTEM,
                title = "USB debugging (ADB) is enabled",
                description = "Developer Options > USB debugging is currently on.",
                whyItMatters = "ADB allows code to be installed and run on your device over a " +
                    "USB or network connection — leave it off unless you're actively developing.",
                fixAction = FixAction.OpenSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            )
        }

        if (canInstallFromUnknownSources()) {
            findings += Finding(
                severity = RiskLevel.MEDIUM,
                category = FindingCategory.SYSTEM,
                title = "Installing from unknown sources is allowed",
                description = "At least one app currently has permission to install packages " +
                    "outside of Google Play.",
                whyItMatters = "Sideloading is the single most common delivery path for " +
                    "Android malware, since it skips app-store review.",
                fixAction = securitySettings,
            )
        }

        if (!hasScreenLock()) {
            findings += Finding(
                severity = RiskLevel.LOW,
                category = FindingCategory.SYSTEM,
                title = "No screen lock configured",
                description = "Your device doesn't currently have a PIN, pattern, or " +
                    "biometric lock set.",
                whyItMatters = "A screen lock is what protects your data if the device is " +
                    "ever lost or stolen.",
                fixAction = securitySettings,
            )
        }

        securityPatchAgeFinding()?.let { findings += it }
        networkAdvisoryFinding()?.let { findings += it }

        return findings
    }

    private fun isProbablyRooted(): Boolean {
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) return true

        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
        )
        if (suPaths.any { File(it).exists() }) return true

        val rootPackages = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.noshufou.android.su",
            "com.koushikdutta.superuser",
        )
        val pm = context.packageManager
        return rootPackages.any { isPackageInstalled(pm, it) }
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean = try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun isAdbEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    } catch (e: Exception) {
        false
    }

    private fun canInstallFromUnknownSources(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }
    } catch (e: Exception) {
        false
    }

    private fun hasScreenLock(): Boolean = try {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km?.isDeviceSecure ?: true
    } catch (e: Exception) {
        true
    }

    /** Flags an Android security patch level older than ~120 days. */
    private fun securityPatchAgeFinding(): Finding? {
        val patch = Build.VERSION.SECURITY_PATCH ?: return null
        val patchDate = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)
        } catch (e: Exception) {
            null
        } ?: return null

        val ageDays = TimeUnit.MILLISECONDS.toDays(Date().time - patchDate.time)
        if (ageDays < 120) return null

        return Finding(
            severity = if (ageDays >= 365) RiskLevel.HIGH else RiskLevel.MEDIUM,
            category = FindingCategory.SYSTEM,
            title = "Outdated security patch level",
            description = "Your Android security patch is dated $patch — about $ageDays days old.",
            whyItMatters = "Security patches close vulnerabilities that are often publicly " +
                "known and actively exploited once a patch ships, so staying current matters.",
            fixAction = FixAction.OpenSystemSettings("android.settings.SYSTEM_UPDATE_SETTINGS"),
        )
    }

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
