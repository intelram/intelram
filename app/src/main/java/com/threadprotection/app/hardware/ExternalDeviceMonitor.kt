package com.threadprotection.app.hardware

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Watches for external devices actually connecting to this phone, over USB and Bluetooth.
 *
 * ## What this really does, and what it cannot do
 *
 * Detection is real. `ACTION_USB_DEVICE_ATTACHED` and `ACTION_ACL_CONNECTED` are broadcast by the
 * OS when a device genuinely connects, and the device details come from `UsbDevice` /
 * `BluetoothDevice` — nothing here is invented or replayed from a script.
 *
 * **Blocking is not real, and this app does not pretend otherwise.** No Android app without root
 * can stop the kernel enumerating a USB device or refuse an incoming Bluetooth ACL connection: by
 * the time an app sees the broadcast, the connection already exists. `BluetoothDevice.removeBond()`
 * is a hidden API and calling it by reflection is neither reliable nor permitted on modern Android.
 *
 * So "Block the device" here means exactly, and only:
 *  - the decision is recorded and remembered, so this app never treats the device as trusted;
 *  - the device is listed as blocked wherever the app shows connected hardware;
 *  - the user is told plainly what they must do themselves — unplug it, or unpair it in Settings —
 *    and offered a direct route to the relevant Settings screen.
 *
 * Presenting that as "blocked, this device can no longer touch your phone" would be a lie, and a
 * dangerous one: a user who believes a malicious cable has been neutralised will leave it plugged
 * in. The UI states the limit instead. See §Platform limitations in the alert copy.
 */
class ExternalDeviceMonitor private constructor(private val context: Context) {

    companion object {
        const val TAG = "TPHardware"

        @Volatile private var instance: ExternalDeviceMonitor? = null

        fun getInstance(context: Context): ExternalDeviceMonitor =
            instance ?: synchronized(this) {
                instance ?: ExternalDeviceMonitor(context.applicationContext).also { instance = it }
            }

        /** USB class 0 means "look at the interfaces instead" — the device defers its identity to
         *  them, so the interface classes are the only honest source of what it actually is. */
        private const val USB_CLASS_DEFERRED = UsbConstants.USB_CLASS_PER_INTERFACE
    }

    private val _devices = MutableStateFlow<List<ExternalDevice>>(emptyList())

    /** Every external device seen this session, connected or not, newest first. */
    val devices: StateFlow<List<ExternalDevice>> = _devices.asStateFlow()

    /** The user's decision per device id. Session-scoped by design — see [DeviceTrust]. */
    private val _trust = MutableStateFlow<Map<String, DeviceTrust>>(emptyMap())
    val trust: StateFlow<Map<String, DeviceTrust>> = _trust.asStateFlow()

    /** The device currently awaiting a decision, or null. Only one alert is shown at a time so a
     *  burst of connections can't stack a pile of modals over each other. */
    private val _pendingAlert = MutableStateFlow<ExternalDevice?>(null)
    val pendingAlert: StateFlow<ExternalDevice?> = _pendingAlert.asStateFlow()

    /** True when Bluetooth connection events can't be read because BLUETOOTH_CONNECT isn't granted.
     *  Surfaced so the UI can say "not watching Bluetooth" rather than implying all-clear. */
    private val _bluetoothBlind = MutableStateFlow(false)
    val bluetoothBlind: StateFlow<Boolean> = _bluetoothBlind.asStateFlow()

    @Volatile private var receiver: BroadcastReceiver? = null

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────

    /** Starts watching. Idempotent: calling it again while already running does nothing. */
    fun start() {
        if (receiver != null) {
            Log.d(TAG, "start: already watching")
            return
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> usbFrom(intent)?.let { onConnected(it) }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> usbFrom(intent)?.let { onDisconnected(it.id) }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> bluetoothFrom(intent)?.let { onConnected(it) }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> bluetoothFrom(intent)?.let { onDisconnected(it.id) }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // These are all system broadcasts, so RECEIVER_NOT_EXPORTED is correct and keeps
                // another app from being able to fake a device-connected event at this receiver.
                context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(r, filter)
            }
            true
        }.onFailure { Log.e(TAG, "start: registerReceiver failed", it) }.getOrDefault(false)

        if (!registered) return
        receiver = r
        _bluetoothBlind.value = !hasBluetoothConnectPermission()
        if (_bluetoothBlind.value) {
            Log.w(TAG, "start: BLUETOOTH_CONNECT not granted — Bluetooth connections can be seen but not identified")
        }
        Log.i(TAG, "start: watching USB attach/detach and Bluetooth ACL connect/disconnect")
        // Anything already plugged in when the app opened never produced a broadcast we could have
        // heard, so enumerate it now — otherwise a device connected before launch is invisible.
        seedAlreadyConnected()
    }

    fun stop() {
        val r = receiver ?: return
        receiver = null
        runCatching { context.unregisterReceiver(r) }
            .onFailure { Log.w(TAG, "stop: unregisterReceiver threw", it) }
        Log.d(TAG, "stop: no longer watching external devices")
    }

    /** Devices attached before this app started listening. */
    private fun seedAlreadyConnected() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val attached = runCatching { usbManager.deviceList.values.toList() }
            .onFailure { Log.w(TAG, "seedAlreadyConnected: deviceList threw", it) }
            .getOrDefault(emptyList())
        attached.forEach { onConnected(describe(it)) }
    }

    // ── events ──────────────────────────────────────────────────────────────────────────────

    private fun onConnected(device: ExternalDevice) {
        val known = _devices.value.firstOrNull { it.id == device.id }
        _devices.update { list ->
            // Merge on the stable id rather than appending: replugging one drive is the same drive,
            // and a duplicate broadcast (the OS does send them) must not produce a second row or a
            // second alert.
            val merged = device.copy(firstSeenMs = known?.firstSeenMs ?: device.firstSeenMs)
            listOf(merged) + list.filterNot { it.id == device.id }
        }

        val decision = _trust.value[device.id] ?: DeviceTrust.UNKNOWN
        when {
            decision == DeviceTrust.ALLOWED_ONCE ->
                Log.i(TAG, "onConnected: ${device.id} reconnected, already allowed for this session")
            decision == DeviceTrust.BLOCKED ->
                // Still worth re-raising: the user said block, and the thing is back. The alert
                // reflects that state rather than silently re-blocking.
                raiseAlert(device)
            known != null && known.connected ->
                Log.d(TAG, "onConnected: duplicate connect event for ${device.id}, ignoring")
            else -> raiseAlert(device)
        }
    }

    private fun raiseAlert(device: ExternalDevice) {
        if (_pendingAlert.value?.id == device.id) return
        if (_pendingAlert.value != null) {
            // One decision at a time. The device is already in `devices`, so it isn't lost — the
            // user reaches it from the connected-devices list once the current alert is answered.
            Log.d(TAG, "raiseAlert: an alert is already pending, queuing ${device.id} into the device list only")
            return
        }
        Log.i(TAG, "raiseAlert: ${device.transportLabel} ${device.kindLabel} \"${device.displayName}\" (${device.hardwareId})")
        _pendingAlert.value = device
    }

    private fun onDisconnected(id: String) {
        _devices.update { list ->
            list.map { if (it.id == id) it.copy(connected = false, lastSeenMs = System.currentTimeMillis()) else it }
        }
        // Don't leave a modal up for something that has already gone away.
        if (_pendingAlert.value?.id == id) {
            Log.d(TAG, "onDisconnected: $id went away while its alert was open — dismissing")
            _pendingAlert.value = null
        }
        Log.d(TAG, "onDisconnected: $id")
    }

    // ── user decisions ──────────────────────────────────────────────────────────────────────

    /**
     * Records that the user blocked this device.
     *
     * This app cannot sever the connection — see the class docs. What it does is refuse to treat
     * the device as trusted, keep it flagged, and tell the user what only they can do.
     */
    fun block(id: String) {
        Log.i(TAG, "block: user blocked $id (recorded — the OS connection itself cannot be severed by an app)")
        _trust.update { it + (id to DeviceTrust.BLOCKED) }
        _pendingAlert.value = null
    }

    /** Grants trust for this session only. Not persisted — a new session asks again. */
    fun allowOnce(id: String) {
        Log.i(TAG, "allowOnce: user allowed $id for this session")
        _trust.update { it + (id to DeviceTrust.ALLOWED_ONCE) }
        _pendingAlert.value = null
    }

    fun dismissAlert() {
        _pendingAlert.value = null
    }

    /** Clears session grants — called on sign-out, so "allowed once" never outlives the session. */
    fun clearSessionTrust() {
        _trust.value = emptyMap()
        _pendingAlert.value = null
    }

    // ── describing what connected ───────────────────────────────────────────────────────────

    private fun usbFrom(intent: Intent): ExternalDevice? {
        val device = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }.getOrNull()
        if (device == null) {
            Log.w(TAG, "usbFrom: broadcast carried no UsbDevice extra")
            return null
        }
        return describe(device)
    }

    private fun describe(device: UsbDevice): ExternalDevice {
        // getSerialNumber() throws SecurityException without USB permission on API 29+, and that is
        // fine — the serial only sharpens identity, it isn't required for it.
        val serial = runCatching { device.serialNumber }.getOrNull()
        val vendorProduct = "%04x:%04x".format(device.vendorId, device.productId)
        return ExternalDevice(
            id = "usb:$vendorProduct" + (serial?.let { ":$it" } ?: ""),
            transport = DeviceTransport.USB,
            kind = usbKind(device),
            name = runCatching { device.productName }.getOrNull(),
            manufacturer = runCatching { device.manufacturerName }.getOrNull(),
            hardwareId = vendorProduct,
            serial = serial,
        )
    }

    /**
     * What a USB device actually is.
     *
     * The device-level class is often 0 ("per interface"), which means the real identity lives in
     * the interfaces — so those are checked too. A composite device that exposes *any* HID
     * interface is treated as an input device: that is precisely how a malicious cable hides a
     * keyboard behind something innocuous.
     */
    private fun usbKind(device: UsbDevice): DeviceKind {
        val interfaceClasses = runCatching {
            (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass }
        }.getOrDefault(emptyList())
        val classes = buildList {
            if (device.deviceClass != USB_CLASS_DEFERRED) add(device.deviceClass)
            addAll(interfaceClasses)
        }
        return when {
            classes.contains(UsbConstants.USB_CLASS_HID) -> DeviceKind.INPUT
            classes.contains(UsbConstants.USB_CLASS_MASS_STORAGE) -> DeviceKind.STORAGE
            classes.contains(UsbConstants.USB_CLASS_COMM) -> DeviceKind.NETWORK
            classes.contains(UsbConstants.USB_CLASS_AUDIO) -> DeviceKind.AUDIO
            classes.contains(UsbConstants.USB_CLASS_STILL_IMAGE) ||
                classes.contains(UsbConstants.USB_CLASS_PRINTER) -> DeviceKind.IMAGING
            else -> DeviceKind.OTHER
        }
    }

    private fun bluetoothFrom(intent: Intent): ExternalDevice? {
        val device = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }.getOrNull()
        if (device == null) {
            Log.w(TAG, "bluetoothFrom: broadcast carried no BluetoothDevice extra")
            return null
        }
        // Without BLUETOOTH_CONNECT every accessor below throws SecurityException. The connection
        // is still real and still worth reporting — it is reported as an unidentified device rather
        // than dropped, because "we saw something connect but can't name it" is true and useful,
        // while silence would read as all-clear.
        val permitted = hasBluetoothConnectPermission()
        if (!permitted) _bluetoothBlind.value = true
        val address = runCatching { device.address }.getOrNull() ?: return null
        val btClass = if (permitted) runCatching { device.bluetoothClass }.getOrNull() else null
        return ExternalDevice(
            id = "bt:$address",
            transport = DeviceTransport.BLUETOOTH,
            kind = bluetoothKind(btClass),
            name = if (permitted) runCatching { device.name }.getOrNull() else null,
            manufacturer = null,
            hardwareId = address,
            bonded = if (permitted) {
                runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
            } else {
                false
            },
        )
    }

    private fun bluetoothKind(btClass: BluetoothClass?): DeviceKind {
        val major = runCatching { btClass?.majorDeviceClass }.getOrNull() ?: return DeviceKind.OTHER
        return when (major) {
            BluetoothClass.Device.Major.PERIPHERAL -> DeviceKind.INPUT
            BluetoothClass.Device.Major.AUDIO_VIDEO -> DeviceKind.AUDIO
            BluetoothClass.Device.Major.NETWORKING -> DeviceKind.NETWORK
            BluetoothClass.Device.Major.IMAGING -> DeviceKind.IMAGING
            BluetoothClass.Device.Major.COMPUTER, BluetoothClass.Device.Major.PHONE -> DeviceKind.COMPUTER
            BluetoothClass.Device.Major.WEARABLE, BluetoothClass.Device.Major.HEALTH -> DeviceKind.WEARABLE
            else -> DeviceKind.OTHER
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
