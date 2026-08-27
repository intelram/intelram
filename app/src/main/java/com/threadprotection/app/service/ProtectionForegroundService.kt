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
import com.threadprotection.app.chat.MeshRelayManager
import com.threadprotection.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 *
 * It also drives the offline mesh chat relay (README §Chat "History" / MeshRelayManager): message
 * hops only happen while some carrier phone is actively listening and gossiping, so this service
 * — already alive continuously while real-time protection is on — starts the relay's accept loop
 * and ticks it periodically, independent of whether the Chat screen is even open.
 */
class ProtectionForegroundService : Service() {

    private var usbReceiver: BroadcastReceiver? = null
    private var btReceiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var meshTickJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        startForeground(NotificationHelper.NOTIF_ID_PERSISTENT, NotificationHelper.persistentNotification(this))
        registerReceivers()
        startMeshRelay()
        startChatListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
        meshTickJob?.cancel()
        MeshRelayManager.getInstance(applicationContext, SettingsRepository(applicationContext)).stopListening()
        super.onDestroy()
    }

    /** Starts accepting inbound relay hops immediately, then ticks (brief discovery + gossip
     *  exchange with anything nearby) on a fixed interval for as long as this service is alive. */
    /**
     * Keeps the chat RFCOMM listener and BLE presence advertising running for as long as
     * protection is on.
     *
     * Root cause this fixes: both used to start only in goChat() and stop the moment the user left
     * the Chat screen. So a phone sitting on the Dashboard — or with the app merely backgrounded —
     * had no server socket at all, and an incoming chat request simply had nothing to connect to.
     * The recipient never saw a request because one never arrived.
     */
    private fun startChatListener() {
        val app = application as? com.threadprotection.app.ThreadProtectionApp ?: return
        com.threadprotection.app.chat.BluetoothChatManager
            .getInstance(app, app.settingsRepository)
            .startListening()
    }

    private fun startMeshRelay() {
        val relay = MeshRelayManager.getInstance(applicationContext, SettingsRepository(applicationContext))
        relay.startListening()
        meshTickJob = scope.launch {
            while (true) {
                delay(MESH_TICK_INTERVAL_MS)
                runCatching { relay.tick() }
            }
        }
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
        /** Every real hop needs both carriers to be listening at the same moment, so this trades
         *  off battery against how quickly a message actually propagates — 90s keeps the radio
         *  usage modest while still being frequent enough for opportunistic contact to matter. */
        private const val MESH_TICK_INTERVAL_MS = 90_000L

        fun start(context: Context) {
            val intent = Intent(context, ProtectionForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProtectionForegroundService::class.java))
        }
    }
}
