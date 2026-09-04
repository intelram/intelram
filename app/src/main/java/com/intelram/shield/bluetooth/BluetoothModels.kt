package com.intelram.shield.bluetooth

import android.os.Build

data class NearbyDevice(
    val address: String,
    val name: String,
    val isBonded: Boolean,
)

data class ChatMessage(
    val text: String,
    val fromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val deviceName: String) : ConnectionState
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState
    data class Failed(val deviceName: String, val reason: String) : ConnectionState
}

/**
 * The runtime permissions Nearby Chat needs, split by API level: Android 12+
 * (API 31) replaced the old BLUETOOTH/BLUETOOTH_ADMIN/location trio with
 * scoped BLUETOOTH_SCAN/CONNECT/ADVERTISE permissions.
 */
object BluetoothPermissions {
    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** On API < 31, Bluetooth discovery silently returns nothing unless system Location is ON. */
    val needsSystemLocationToggle: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
}
