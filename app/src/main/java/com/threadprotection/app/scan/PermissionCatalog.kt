package com.threadprotection.app.scan

/**
 * Plain-language names for Android's dangerous permissions, matching the "plain words, no
 * jargon" tone from the design spec — README §Design/§App permissions.
 */
object PermissionCatalog {

    data class Entry(val plainName: String, val baselineRisky: Boolean)

    private val CATALOG: Map<String, Entry> = mapOf(
        "android.permission.READ_SMS" to Entry("Read your text messages", true),
        "android.permission.RECEIVE_SMS" to Entry("Read incoming text messages", true),
        "android.permission.SEND_SMS" to Entry("Send text messages", true),
        "android.permission.READ_CONTACTS" to Entry("Your contacts", false),
        "android.permission.WRITE_CONTACTS" to Entry("Edit your contacts", true),
        "android.permission.RECORD_AUDIO" to Entry("Microphone", false),
        "android.permission.CAMERA" to Entry("Camera", false),
        "android.permission.ACCESS_FINE_LOCATION" to Entry("Location while using the app", false),
        "android.permission.ACCESS_COARSE_LOCATION" to Entry("Approximate location", false),
        "android.permission.ACCESS_BACKGROUND_LOCATION" to Entry("Location, all the time", true),
        "android.permission.READ_EXTERNAL_STORAGE" to Entry("Your photos and files", false),
        "android.permission.WRITE_EXTERNAL_STORAGE" to Entry("Change or delete your files", true),
        "android.permission.MANAGE_EXTERNAL_STORAGE" to Entry("All your files", true),
        "android.permission.READ_MEDIA_IMAGES" to Entry("Your photos", false),
        "android.permission.READ_MEDIA_VIDEO" to Entry("Your videos", false),
        "android.permission.READ_MEDIA_AUDIO" to Entry("Your audio files", false),
        "android.permission.READ_CALL_LOG" to Entry("Your call history", true),
        "android.permission.WRITE_CALL_LOG" to Entry("Edit your call history", true),
        "android.permission.CALL_PHONE" to Entry("Make phone calls", true),
        "android.permission.READ_PHONE_STATE" to Entry("Your phone status and identity", true),
        "android.permission.READ_PHONE_NUMBERS" to Entry("Your phone number", true),
        "android.permission.ANSWER_PHONE_CALLS" to Entry("Answer phone calls", true),
        "android.permission.BODY_SENSORS" to Entry("Body sensors (heart rate etc.)", false),
        "android.permission.ACTIVITY_RECOGNITION" to Entry("Your physical activity", false),
        "android.permission.SYSTEM_ALERT_WINDOW" to Entry("Display over other apps", true),
        "android.permission.POST_NOTIFICATIONS" to Entry("Send you notifications", false),
        "android.permission.BLUETOOTH_CONNECT" to Entry("Connect to nearby Bluetooth devices", false),
        "android.permission.BLUETOOTH_SCAN" to Entry("Scan for nearby Bluetooth devices", false),
        "android.permission.NEARBY_WIFI_DEVICES" to Entry("Find nearby Wi‑Fi devices", false),
        "android.permission.GET_ACCOUNTS" to Entry("See the accounts on this phone", false),
        "android.permission.USE_FINGERPRINT" to Entry("Use your fingerprint", false),
        "android.permission.USE_BIOMETRIC" to Entry("Use your biometrics", false),
        "android.permission.PROCESS_OUTGOING_CALLS" to Entry("See numbers you call", true),
    )

    fun lookup(permission: String): Entry? = CATALOG[permission]

    /** Special-cased not-a-manifest-permission risk: accessibility service access. */
    val accessibilityEntry = Entry("Control your screen (accessibility)", true)
}
