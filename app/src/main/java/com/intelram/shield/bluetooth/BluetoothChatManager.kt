package com.intelram.shield.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val APP_UUID: UUID = UUID.fromString("8e3a7c9e-8f1b-4c2a-9d3e-2b6f7a1c5d40")
private const val SERVICE_NAME = "ThreatProtectionChat"

/**
 * Classic Bluetooth (RFCOMM) peer discovery and chat.
 *
 * The classic bug in DIY Bluetooth chat apps — "phone A can find phone B, but
 * B can never find A" — comes from an asymmetric implementation: only one
 * side calls `startDiscovery()`, or only one side is made discoverable, or
 * only one side runs a listening server socket. Every method here is meant
 * to be called on BOTH ends, symmetrically: every device that opens Nearby
 * Chat becomes discoverable, actively scans, AND listens for incoming
 * connections at the same time — so either phone can find and connect to
 * the other, in either direction.
 */
class BluetoothChatManager(context: Context) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val deviceRegistry = ConcurrentHashMap<String, BluetoothDevice>()
    private var scope: CoroutineScope? = null
    private var serverJob: Job? = null
    private var connectionJob: Job? = null
    private var activeSocket: BluetoothSocket? = null
    private var receiverRegistered = false

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<NearbyDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    val isBluetoothSupported: Boolean get() = adapter != null
    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    fun enableBluetoothIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    fun discoverableIntent(durationSeconds: Int = 300): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSeconds)

    fun bondedDevices(): List<NearbyDevice> {
        val bonded = try {
            adapter?.bondedDevices.orEmpty()
        } catch (e: SecurityException) {
            emptySet()
        }
        return bonded.map { device ->
            deviceRegistry[device.address] = device
            device.toNearbyDevice(isBonded = true)
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    deviceRegistry[device.address] = device
                    val isBonded = try {
                        device.bondState == BluetoothDevice.BOND_BONDED
                    } catch (e: SecurityException) {
                        false
                    }
                    val nearby = device.toNearbyDevice(isBonded)
                    _discoveredDevices.value = (_discoveredDevices.value.filterNot { it.address == nearby.address } + nearby)
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _isDiscovering.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _isDiscovering.value = false
            }
        }
    }

    /** Call when the Nearby Chat screen becomes visible. Starts listening + registers receivers. */
    fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(appContext, discoveryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }

        startServer(newScope)
    }

    /** Call when leaving the Nearby Chat screen. Releases every Bluetooth resource. */
    fun stop() {
        try {
            adapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            // Permission was revoked mid-session; nothing to clean up on our end.
        }
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(discoveryReceiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
            receiverRegistered = false
        }
        closeActiveSocket()
        try {
            serverSocketRef?.close()
        } catch (e: IOException) {
            // Ignore — we're tearing down anyway.
        }
        serverSocketRef = null
        scope?.cancel()
        scope = null
        _isDiscovering.value = false
        _discoveredDevices.value = emptyList()
    }

    fun startDiscovery() {
        try {
            adapter?.cancelDiscovery()
            _discoveredDevices.value = emptyList()
            adapter?.startDiscovery()
        } catch (e: SecurityException) {
            // Caller is responsible for checking permissions first; ignore if it slipped through.
        }
    }

    private var serverSocketRef: BluetoothServerSocket? = null

    private fun startServer(scope: CoroutineScope) {
        serverJob = scope.launch {
            try {
                val socket = adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID) ?: return@launch
                serverSocketRef = socket
                while (isActive) {
                    val incoming = try {
                        socket.accept()
                    } catch (e: IOException) {
                        null
                    } ?: break
                    attachSocket(incoming, incoming.remoteDevice)
                }
            } catch (e: SecurityException) {
                // Missing BLUETOOTH_CONNECT permission — surfaced via connectionState by callers checking permissions up front.
            }
        }
    }

    fun connectTo(address: String) {
        val device = deviceRegistry[address] ?: return
        val activeScope = scope ?: return
        connectionJob?.cancel()
        connectionJob = activeScope.launch {
            _connectionState.value = ConnectionState.Connecting(device.safeName())
            try {
                adapter?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                attachSocket(socket, device)
            } catch (e: IOException) {
                _connectionState.value = ConnectionState.Failed(device.safeName(), e.message ?: "Connection failed")
            } catch (e: SecurityException) {
                _connectionState.value = ConnectionState.Failed(device.safeName(), "Missing Bluetooth permission")
            }
        }
    }

    private fun attachSocket(socket: BluetoothSocket, device: BluetoothDevice) {
        closeActiveSocket()
        activeSocket = socket
        _connectionState.value = ConnectionState.Connected(device.safeName(), device.address)
        _messages.value = emptyList()

        val activeScope = scope ?: return
        activeScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    _messages.value = _messages.value + ChatMessage(text = line, fromMe = false)
                }
            } catch (e: IOException) {
                // Peer disconnected or the socket was closed locally.
            } finally {
                if (activeSocket === socket) {
                    _connectionState.value = ConnectionState.Disconnected
                    activeSocket = null
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val socket = activeSocket ?: return
        val activeScope = scope ?: return
        activeScope.launch {
            try {
                val out: OutputStream = socket.outputStream
                out.write((text + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                _messages.value = _messages.value + ChatMessage(text = text, fromMe = true)
            } catch (e: IOException) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    fun disconnect() {
        closeActiveSocket()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun closeActiveSocket() {
        try {
            activeSocket?.close()
        } catch (e: IOException) {
            // Already closed.
        }
        activeSocket = null
    }

    private fun BluetoothDevice.toNearbyDevice(isBonded: Boolean) =
        NearbyDevice(address = address, name = safeName(), isBonded = isBonded)

    private fun BluetoothDevice.safeName(): String = try {
        name ?: address
    } catch (e: SecurityException) {
        address
    }
}
