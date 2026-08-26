package com.threadprotection.app.scan

/**
 * Plain-language names *and* real descriptions for Android's dangerous permissions, matching the
 * "plain words, no jargon" tone from the design spec — README §Design/§App permissions. Every
 * description states exactly what that permission actually grants, per Android's own permission
 * semantics (developer.android.com/reference/android/Manifest.permission) — not a marketing gloss.
 */
object PermissionCatalog {

    data class Entry(val plainName: String, val description: String, val baselineRisky: Boolean)

    private val CATALOG: Map<String, Entry> = mapOf(
        "android.permission.READ_SMS" to Entry(
            "Read your text messages",
            "Lets the app read every text message stored on your device, including sender numbers and full message content.",
            true,
        ),
        "android.permission.RECEIVE_SMS" to Entry(
            "Read incoming text messages",
            "Lets the app monitor and read incoming text messages the moment they arrive, before you even open them.",
            true,
        ),
        "android.permission.SEND_SMS" to Entry(
            "Send text messages",
            "Lets the app send text messages on your behalf, which can run up charges or be abused for spam and fraud.",
            true,
        ),
        "android.permission.READ_CONTACTS" to Entry(
            "Your contacts",
            "Lets the app read your entire contacts list — names, numbers, emails and any notes you've saved.",
            false,
        ),
        "android.permission.WRITE_CONTACTS" to Entry(
            "Edit your contacts",
            "Lets the app add, change or delete entries in your contacts list without asking each time.",
            true,
        ),
        "android.permission.RECORD_AUDIO" to Entry(
            "Microphone",
            "Lets the app use the microphone to record audio at any time while it's running.",
            false,
        ),
        "android.permission.CAMERA" to Entry(
            "Camera",
            "Lets the app take photos and record video using any camera on this device.",
            false,
        ),
        "android.permission.ACCESS_FINE_LOCATION" to Entry(
            "Location while using the app",
            "Lets the app see your precise GPS location while you're actively using it.",
            false,
        ),
        "android.permission.ACCESS_COARSE_LOCATION" to Entry(
            "Approximate location",
            "Lets the app estimate your location to roughly a city block, using cell towers and Wi-Fi rather than exact GPS.",
            false,
        ),
        "android.permission.ACCESS_BACKGROUND_LOCATION" to Entry(
            "Location, all the time",
            "Lets the app track your location even when you're not using it, including while it runs in the background.",
            true,
        ),
        "android.permission.READ_EXTERNAL_STORAGE" to Entry(
            "Your photos and files",
            "Lets the app read photos, videos and other files stored on your device.",
            false,
        ),
        "android.permission.WRITE_EXTERNAL_STORAGE" to Entry(
            "Change or delete your files",
            "Lets the app create, modify or delete files stored on your device.",
            true,
        ),
        "android.permission.MANAGE_EXTERNAL_STORAGE" to Entry(
            "All your files",
            "Lets the app read and modify virtually every file on your device's storage, bypassing the usual per-file restrictions.",
            true,
        ),
        "android.permission.READ_MEDIA_IMAGES" to Entry(
            "Your photos",
            "Lets the app view every photo in your gallery.",
            false,
        ),
        "android.permission.READ_MEDIA_VIDEO" to Entry(
            "Your videos",
            "Lets the app view every video saved on your device.",
            false,
        ),
        "android.permission.READ_MEDIA_AUDIO" to Entry(
            "Your audio files",
            "Lets the app access every audio file stored on your device.",
            false,
        ),
        "android.permission.READ_CALL_LOG" to Entry(
            "Your call history",
            "Lets the app see who you've called and who has called you, including timestamps and durations.",
            true,
        ),
        "android.permission.WRITE_CALL_LOG" to Entry(
            "Edit your call history",
            "Lets the app add or remove entries from your call history.",
            true,
        ),
        "android.permission.CALL_PHONE" to Entry(
            "Make phone calls",
            "Lets the app place phone calls directly, without going through the dialer or asking you to confirm each time.",
            true,
        ),
        "android.permission.READ_PHONE_STATE" to Entry(
            "Your phone status and identity",
            "Lets the app see your phone number, device identifiers, network status, and whether a call is currently active.",
            true,
        ),
        "android.permission.READ_PHONE_NUMBERS" to Entry(
            "Your phone number",
            "Lets the app read the phone number assigned to this device.",
            true,
        ),
        "android.permission.ANSWER_PHONE_CALLS" to Entry(
            "Answer phone calls",
            "Lets the app answer incoming phone calls on your behalf.",
            true,
        ),
        "android.permission.BODY_SENSORS" to Entry(
            "Body sensors (heart rate etc.)",
            "Lets the app read data from sensors that monitor your body, such as a heart-rate sensor.",
            false,
        ),
        "android.permission.ACTIVITY_RECOGNITION" to Entry(
            "Your physical activity",
            "Lets the app detect your physical activity — walking, running, driving and similar.",
            false,
        ),
        "android.permission.SYSTEM_ALERT_WINDOW" to Entry(
            "Display over other apps",
            "Lets the app draw its own windows on top of every other app, including over system screens.",
            true,
        ),
        "android.permission.POST_NOTIFICATIONS" to Entry(
            "Send you notifications",
            "Lets the app show notifications in your notification shade.",
            false,
        ),
        "android.permission.BLUETOOTH_CONNECT" to Entry(
            "Connect to nearby Bluetooth devices",
            "Lets the app connect to and exchange data with Bluetooth devices you've already paired.",
            false,
        ),
        "android.permission.BLUETOOTH_SCAN" to Entry(
            "Scan for nearby Bluetooth devices",
            "Lets the app search for nearby Bluetooth devices, including ones you haven't paired with.",
            false,
        ),
        "android.permission.NEARBY_WIFI_DEVICES" to Entry(
            "Find nearby Wi‑Fi devices",
            "Lets the app discover and connect to nearby devices over Wi-Fi, without needing your location.",
            false,
        ),
        "android.permission.GET_ACCOUNTS" to Entry(
            "See the accounts on this phone",
            "Lets the app see the list of accounts registered on this device — Google, email and similar — though not their passwords.",
            false,
        ),
        "android.permission.USE_FINGERPRINT" to Entry(
            "Use your fingerprint",
            "Lets the app ask you to authenticate using the fingerprint sensor.",
            false,
        ),
        "android.permission.USE_BIOMETRIC" to Entry(
            "Use your biometrics",
            "Lets the app ask you to authenticate with face, fingerprint or other biometrics set up on this device.",
            false,
        ),
        "android.permission.PROCESS_OUTGOING_CALLS" to Entry(
            "See numbers you call",
            "Lets the app see the number you're dialing, and optionally redirect or end the call before it connects.",
            true,
        ),
    )

    fun lookup(permission: String): Entry? = CATALOG[permission]

    /** Special-cased not-a-manifest-permission risk: accessibility service access. */
    val accessibilityEntry = Entry(
        "Control your screen (accessibility)",
        "Lets the app read everything shown on your screen and simulate taps and gestures on your behalf — the same access used by legitimate screen readers, but also by malware that steals passwords or clicks through permission dialogs unattended.",
        true,
    )
}
