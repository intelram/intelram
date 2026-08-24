package com.threadprotection.app.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.threadprotection.app.data.HwDevice
import com.threadprotection.app.data.HwSim
import com.threadprotection.app.data.HwVerdict

/**
 * Real hardware-watch signals from Android's own APIs — README's "Hardware watch" §Backend
 * requirements: `UsbManager` for attached USB devices, `BluetoothAdapter` for paired devices,
 * `BatteryManager` for the charging source. What Android genuinely cannot expose without root —
 * whether a charger's data lines are actively transferring data, or a keylogger's exact keystroke
 * stream — is not fabricated; a HID-class USB device attaching is flagged as worth a look, not
 * asserted as malicious.
 */
class HardwareWatcher(private val context: Context) {

    fun scan(): List<HwDevice> {
        val devices = mutableListOf<HwDevice>()
        devices += usbDevices()
        devices += bluetoothDevices()
        devices += batteryStatus()
        return devices
    }

    /** A HID-class device (keyboard/mouse profile) attached via USB is the closest real signal to a BadUSB alert. */
    fun suspiciousHidAlert(): HwSim? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val hid = runCatching {
            usbManager.deviceList.values.firstOrNull { device ->
                (0 until device.interfaceCount).any { i -> device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID }
            }
        }.getOrNull() ?: return null
        return HwSim(
            name = hid.productName ?: "Unknown USB HID device",
            kind = "Plugged in · identifies itself as a keyboard/input device",
            verdict = HwVerdict.WARN,
            risk = 78,
            why = "A device that behaves like a keyboard or mouse is connected. Genuine accessories are fine, but this is exactly how a BadUSB keylogger presents itself.",
            advice = "If you don't recognise this device, unplug it and avoid reusing that cable or dock.",
        )
    }

    private fun usbDevices(): List<HwDevice> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        val devices = runCatching { usbManager.deviceList.values.toList() }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            return listOf(HwDevice("USB", "Nothing connected to a USB port right now", ok = true))
        }
        return devices.map { device ->
            val isHid = runCatching {
                (0 until device.interfaceCount).any { i -> device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID }
            }.getOrDefault(false)
            HwDevice(
                name = device.productName ?: "USB device (${device.vendorId}:${device.productId})",
                detail = if (isHid) "Connected now · behaves like a keyboard or input device" else "Connected now",
                ok = !isHid,
            )
        }
    }

    private fun bluetoothDevices(): List<HwDevice> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val adapter: BluetoothAdapter = manager.adapter ?: return listOf(HwDevice("Bluetooth", "This device has no Bluetooth radio", ok = true))
        if (!adapter.isEnabled) return listOf(HwDevice("Bluetooth", "Off", ok = true))
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return listOf(HwDevice("Bluetooth", "Grant the Bluetooth permission to check paired devices", ok = true))
        }
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        if (bonded.isEmpty()) return listOf(HwDevice("Bluetooth", "No paired devices", ok = true))
        return bonded.map { device ->
            val name = runCatching { device.name }.getOrNull() ?: "Unnamed device"
            HwDevice(name, "Paired Bluetooth device", ok = true)
        }
    }

    private fun batteryStatus(): List<HwDevice> {
        val intent = runCatching {
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return emptyList()
        val pluggedType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val detail = when (pluggedType) {
            BatteryManager.BATTERY_PLUGGED_USB -> "Charging via USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "Charging via AC adapter"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Charging wirelessly"
            else -> "Not charging"
        }
        return listOf(HwDevice("Power / charging", detail, ok = true))
    }
}
