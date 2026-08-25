package com.threadprotection.app.scan

import android.os.Build

data class OsDeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String,
    val kernelVersion: String,
    val buildFingerprint: String,
)

/** Real `Build.*` fields — no lookup, no network, always available instantly. */
object DeviceInfo {
    fun current(): OsDeviceInfo = OsDeviceInfo(
        manufacturer = Build.MANUFACTURER ?: "Unknown",
        model = Build.MODEL ?: "Unknown",
        androidVersion = Build.VERSION.RELEASE ?: "Unknown",
        sdkInt = Build.VERSION.SDK_INT,
        securityPatch = Build.VERSION.SECURITY_PATCH?.ifBlank { "Unknown" } ?: "Unknown",
        kernelVersion = System.getProperty("os.version") ?: "Unknown",
        buildFingerprint = Build.FINGERPRINT ?: "Unknown",
    )
}
