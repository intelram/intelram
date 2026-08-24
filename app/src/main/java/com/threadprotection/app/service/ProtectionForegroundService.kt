package com.threadprotection.app.service

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.threadprotection.app.MainActivity

/**
 * Keeps the hardware watch (USB/Bluetooth) live even when the app itself is closed — README's
 * "Even if the customer closes this application, make sure this application works in the
 * background." A foreground service with a low-priority persistent notification is the only
 * Android-sanctioned way to do this reliably post-Android 8: a plain background service gets
 * killed by the OS within minutes once the app leaves the foreground.
 *
 * This does *not* run the full PackageManager/port scan continuously — that would be a battery
 * drain no real security app does. It watches the two things that are genuinely event-driven
 * (a device attaching, a device pairing) and posts a real notification the moment one happens.
 */
class ProtectionForegroundService : Service() {

    private var usbReceiver: BroadcastReceiver? = null
    private var btReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        startForeground(NotificationHelper.NOTIF_ID_PERSISTENT, NotificationHelper.persistentNotification(this))
        registerReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    private fun registerReceivers() {
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                } ?: return
                val isHid = runCatching {
                    (0 until device.interfaceCount).any { i -> device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID }
                }.getOrDefault(false)
                if (isHid) {
                    NotificationHelper.postAlert(
                        context,
                        "Unrecognised input device connected",
                        "${device.productName ?: "A USB device"} identifies itself as a keyboard or mouse. If you don't recognise it, unplug it.",
                        MainActivity.TARGET_DASHBOARD,
                    )
                }
            }
        }
        registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))

        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val name = runCatching { device.name }.getOrNull() ?: "A Bluetooth device"
                NotificationHelper.postAlert(
                    context,
                    "Bluetooth device connected",
                    "$name just connected. Thread Protection is watching it.",
                    MainActivity.TARGET_DASHBOARD,
                )
            }
        }
        registerReceiver(btReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ProtectionForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProtectionForegroundService::class.java))
        }
    }
}
