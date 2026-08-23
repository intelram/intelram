package com.intelram.shield.scan

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.io.File

/** Device-wide security posture checks (root, dev settings, screen lock, etc). */
class DeviceScanner(private val context: Context) {

    fun runChecks(): List<DeviceCheck> {
        val checks = mutableListOf<DeviceCheck>()

        if (isProbablyRooted()) {
            checks += DeviceCheck(
                RiskLevel.HIGH,
                "Device may be rooted",
                "Root indicators were found (su binary, root-management app, or a " +
                    "test-keys build). Rooting removes Android's app-sandbox " +
                    "protections and increases exposure to malware.",
            )
        }

        if (isAdbEnabled()) {
            checks += DeviceCheck(
                RiskLevel.MEDIUM,
                "USB debugging (ADB) is enabled",
                "Developer Options > USB debugging is on. Leave it off unless you're " +
                    "actively developing — it allows code execution over a USB/network " +
                    "connection.",
            )
        }

        if (canInstallFromUnknownSources()) {
            checks += DeviceCheck(
                RiskLevel.MEDIUM,
                "Installs from unknown sources are allowed",
                "This app (or another) currently has permission to install packages " +
                    "outside of Google Play, which is the most common malware delivery " +
                    "path on Android.",
            )
        }

        if (!hasScreenLock()) {
            checks += DeviceCheck(
                RiskLevel.LOW,
                "No screen lock configured",
                "Setting a PIN, pattern, or biometric lock protects local data if the " +
                    "device is lost or stolen.",
            )
        }

        return checks
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
}
