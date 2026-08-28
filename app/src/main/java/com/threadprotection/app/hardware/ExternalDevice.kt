package com.threadprotection.app.hardware

/** How an external device reached this phone. */
enum class DeviceTransport { USB, BLUETOOTH }

/** What the device claims to be, taken from the class it reports over the wire. */
enum class DeviceKind {
    /** Keyboards, mice, game controllers. The class BadUSB attacks impersonate: a device that can
     *  type has, in effect, the user's own hands. */
    INPUT,

    /** Flash drives, card readers, phones in file-transfer mode — anything that can move files. */
    STORAGE,

    /** Headsets, speakers, microphones. */
    AUDIO,

    /** Ethernet adapters, tethering, modems — anything that can carry traffic off the device. */
    NETWORK,

    /** Cameras, scanners, printers. */
    IMAGING,

    /** A general-purpose computer or phone on the other end. */
    COMPUTER,

    /** Wearables and health devices. */
    WEARABLE,

    OTHER,
}

/** The decision the user made about a device, if any. */
enum class DeviceTrust {
    /** No decision yet — the alert is what asks for one. */
    UNKNOWN,

    /** The user said block. See ExternalDeviceMonitor for what this app can and cannot enforce. */
    BLOCKED,

    /** Allowed for this session only. Deliberately not persisted: "I know this device" is a
     *  statement about right now, and a permanent grant is not what the user was asked for. */
    ALLOWED_ONCE,
}

/**
 * One external device seen connecting to this phone, built from what the OS actually reported.
 *
 * Every field comes from a real platform API — `UsbDevice` for USB, `BluetoothDevice` plus
 * `BluetoothClass` for Bluetooth. Fields the OS didn't supply stay null rather than being filled
 * with a plausible-looking guess; the UI says so instead.
 */
data class ExternalDevice(
    /**
     * Stable identity for this device across connect/disconnect cycles.
     *
     * USB uses vendor:product plus the serial number when the device supplies one — two identical
     * flash drives really are different devices, and the serial is the only thing separating them.
     * Bluetooth uses the MAC address. Without a stable key, unplugging and replugging one drive
     * would re-prompt as though it were new every time.
     */
    val id: String,
    val transport: DeviceTransport,
    val kind: DeviceKind,
    /** Product name as reported by the device, or null if it didn't supply one. */
    val name: String?,
    /** Manufacturer as reported, or null. */
    val manufacturer: String?,
    /** USB vendor:product in hex, or the Bluetooth address — shown so the user can tell two
     *  similarly-named devices apart. */
    val hardwareId: String,
    val serial: String? = null,
    /** True when the OS says this device is already paired/bonded (Bluetooth only). Something the
     *  user paired themselves earlier is a weaker risk signal than something that isn't. */
    val bonded: Boolean = false,
    val firstSeenMs: Long = System.currentTimeMillis(),
    val lastSeenMs: Long = System.currentTimeMillis(),
    val connected: Boolean = true,
) {
    /** Best available human label — never a fabricated one. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: manufacturer?.takeIf { it.isNotBlank() }?.let { "$it device" }
            ?: when (transport) {
                DeviceTransport.USB -> "Unnamed USB device"
                DeviceTransport.BLUETOOTH -> "Unnamed Bluetooth device"
            }

    val transportLabel: String
        get() = when (transport) {
            DeviceTransport.USB -> "USB"
            DeviceTransport.BLUETOOTH -> "Bluetooth"
        }

    val kindLabel: String
        get() = when (kind) {
            DeviceKind.INPUT -> "Keyboard / input device"
            DeviceKind.STORAGE -> "Storage device"
            DeviceKind.AUDIO -> "Audio device"
            DeviceKind.NETWORK -> "Network adapter"
            DeviceKind.IMAGING -> "Camera / printer"
            DeviceKind.COMPUTER -> "Computer or phone"
            DeviceKind.WEARABLE -> "Wearable"
            DeviceKind.OTHER -> "Unrecognised device type"
        }
}

/**
 * How risky a newly connected device is, and why — derived from what it can *do*, not from a
 * blocklist of names. A device that can type keystrokes is dangerous whatever it calls itself,
 * which is the entire point of a BadUSB attack.
 */
data class DeviceRisk(val level: DeviceRiskLevel, val why: String, val advice: String)

enum class DeviceRiskLevel { HIGH, MEDIUM, LOW }

object DeviceRiskAssessor {

    fun assess(device: ExternalDevice): DeviceRisk = when {
        device.kind == DeviceKind.INPUT && device.transport == DeviceTransport.USB -> DeviceRisk(
            DeviceRiskLevel.HIGH,
            "This connected as a keyboard or input device. Anything that can type can run commands, " +
                "change settings and install software as if you were doing it yourself — which is how " +
                "\"BadUSB\" cables and chargers attack a phone. If you only plugged in a charger or a " +
                "drive, it should not be claiming to be a keyboard.",
            "If you did not deliberately connect a keyboard, unplug it now and avoid that cable, dock or charger.",
        )

        device.kind == DeviceKind.INPUT -> DeviceRisk(
            DeviceRiskLevel.HIGH,
            "This connected as a keyboard or input device over Bluetooth. Something that can type can " +
                "act as you — open apps, change settings, approve prompts — without touching your screen.",
            "If you did not pair a keyboard yourself, unpair it in Bluetooth settings.",
        )

        device.kind == DeviceKind.STORAGE -> DeviceRisk(
            DeviceRiskLevel.MEDIUM,
            "This connected as a storage device, so it can carry files on and off your phone. Public " +
                "charging points and shared drives are a common way for files to be copied off a phone, " +
                "or malware copied onto one.",
            "Only continue if you own this drive or trust where it came from.",
        )

        device.kind == DeviceKind.NETWORK -> DeviceRisk(
            DeviceRiskLevel.MEDIUM,
            "This connected as a network adapter. Traffic from your phone can be routed through it, " +
                "which means whoever controls it can watch or redirect what you connect to.",
            "Only continue if this is your own adapter or tethering setup.",
        )

        device.kind == DeviceKind.COMPUTER -> DeviceRisk(
            DeviceRiskLevel.MEDIUM,
            "A computer or another phone connected to this device. Depending on what you approve, it " +
                "may be able to browse your files or send commands.",
            "Only continue if this is your own computer.",
        )

        device.kind == DeviceKind.OTHER -> DeviceRisk(
            DeviceRiskLevel.MEDIUM,
            "This device did not report a recognisable type. Not knowing what something is doesn't make " +
                "it safe — a device that hides its class is worth a second look.",
            "Only continue if you recognise this device.",
        )

        else -> DeviceRisk(
            DeviceRiskLevel.LOW,
            "This connected as a ${device.kindLabel.lowercase()}. That type has limited access to your " +
                "phone, but it is still new to this app.",
            "Continue if you recognise it.",
        )
    }
}
