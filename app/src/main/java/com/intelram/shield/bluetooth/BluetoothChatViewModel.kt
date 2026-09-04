package com.intelram.shield.bluetooth

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BluetoothChatViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = BluetoothChatManager(application)

    val isDiscovering: StateFlow<Boolean> = manager.isDiscovering
    val discoveredDevices: StateFlow<List<NearbyDevice>> = manager.discoveredDevices
    val connectionState: StateFlow<ConnectionState> = manager.connectionState
    val messages: StateFlow<List<ChatMessage>> = manager.messages

    private val _bondedDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val bondedDevices: StateFlow<List<NearbyDevice>> = _bondedDevices.asStateFlow()

    val isBluetoothSupported: Boolean get() = manager.isBluetoothSupported

    fun isBluetoothEnabled(): Boolean = manager.isBluetoothEnabled

    fun isSystemLocationEnabled(context: Context): Boolean {
        if (!BluetoothPermissions.needsSystemLocationToggle) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun enableBluetoothIntent(): Intent = manager.enableBluetoothIntent()
    fun discoverableIntent(): Intent = manager.discoverableIntent()

    fun onScreenEntered() {
        manager.start()
        refreshBondedDevices()
    }

    fun onScreenLeft() {
        manager.stop()
    }

    fun refreshBondedDevices() {
        _bondedDevices.value = manager.bondedDevices()
    }

    fun startDiscovery() {
        manager.startDiscovery()
    }

    fun connectTo(address: String) {
        manager.connectTo(address)
    }

    fun disconnect() {
        manager.disconnect()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        manager.sendMessage(text.trim())
    }

    override fun onCleared() {
        super.onCleared()
        manager.stop()
    }
}
