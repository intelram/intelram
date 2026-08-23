package com.intelram.shield.scan

/**
 * On-device threat intelligence used by the scanner. The name/hash lists below are
 * illustrative samples (naming patterns commonly seen in public malware writeups),
 * not a live feed. Swap [knownMalwarePackageNames] / [knownMalwareSha256] for a
 * real, regularly-updated threat-intel source before relying on this in production.
 */
object ThreatIntel {

    val knownMalwarePackageNames: Set<String> = setOf(
        "com.android.provider.telephony.service",
        "com.system.update.helper",
        "com.google.update.installer",
        "com.android.systemsecurity",
        "com.security.center.protect",
    )

    val knownMalwareSha256: Set<String> = emptySet()

    val dangerousPermissions: Set<String> = setOf(
        "android.permission.SEND_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CONTACTS",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
    )

    /**
     * Permission combinations that, together, are a much stronger signal than any
     * single permission alone (classic patterns for SMS-fraud and overlay/banking
     * trojans, remote-access tools, etc).
     */
    val riskyPermissionCombos: List<Pair<String, Set<String>>> = listOf(
        "SMS interception + background start" to setOf(
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        ),
        "Overlay + Accessibility (banking-trojan pattern)" to setOf(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
        ),
        "Silent install capability" to setOf(
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        ),
        "Covert audio/video capture" to setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        ),
    )
}
