package com.intelram.shield.bluetooth

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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

    /**
     * Both manager.start() (registers a BroadcastReceiver) and
     * bondedDevices() (reads BluetoothAdapter.bondedDevices) make a Binder
     * call into the system Bluetooth service — normally fast, but a
     * synchronous IPC call is still not something to do on the caller's
     * thread, since this is invoked from a Compose LaunchedEffect on the
     * main dispatcher.
     */
    fun onScreenEntered() {
        viewModelScope.launch(Dispatchers.IO) {
            manager.start()
            _bondedDevices.value = manager.bondedDevices()
        }
    }

    fun onScreenLeft() {
        viewModelScope.launch(Dispatchers.IO) { manager.stop() }
    }

    fun refreshBondedDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            _bondedDevices.value = manager.bondedDevices()
        }
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
