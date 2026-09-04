package com.intelram.shield.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.intelram.shield.bluetooth.BluetoothChatViewModel
import com.intelram.shield.bluetooth.BluetoothPermissions
import com.intelram.shield.bluetooth.ConnectionState
import com.intelram.shield.bluetooth.NearbyDevice
import com.intelram.shield.ui.components.PrimaryButton
import com.intelram.shield.ui.components.SecondaryButton
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.GreenSoft
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.InkSoft
import com.intelram.shield.ui.theme.Surface

@Composable
fun NearbyChatScreen(viewModel: BluetoothChatViewModel, onOpenConversation: () -> Unit) {
    val context = LocalContext.current
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val discovered by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val bonded by viewModel.bondedDevices.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    fun hasAllPermissions() = BluetoothPermissions.required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var hasPermissions by remember { mutableStateOf(hasAllPermissions()) }
    var bluetoothEnabled by remember { mutableStateOf(viewModel.isBluetoothEnabled()) }
    var locationEnabled by remember { mutableStateOf(viewModel.isSystemLocationEnabled(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermissions = results.values.all { it }
    }
    val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothEnabled = viewModel.isBluetoothEnabled()
    }
    val discoverableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    // Re-check every gate when returning to the app (e.g. after the user
    // flips Bluetooth/Location from Settings or a quick-settings tile,
    // not just via our own buttons) so the UI never shows a stale gate.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermissions = hasAllPermissions()
                bluetoothEnabled = viewModel.isBluetoothEnabled()
                locationEnabled = viewModel.isSystemLocationEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) onOpenConversation()
    }

    val allDevices = remember(bonded, discovered) {
        (bonded + discovered.filterNot { d -> bonded.any { it.address == d.address } })
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.padding(24.dp, 48.dp, 24.dp, 8.dp)) {
            Text("Nearby Chat", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Find another phone with Threat Protection nearby and chat over Bluetooth",
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
            )
        }

        when {
            !viewModel.isBluetoothSupported -> Message("This device doesn't support Bluetooth.")
            !hasPermissions -> PermissionGate {
                permissionLauncher.launch(BluetoothPermissions.required())
            }
            !bluetoothEnabled -> EnableBluetoothGate {
                enableBtLauncher.launch(viewModel.enableBluetoothIntent())
            }
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                if (BluetoothPermissions.needsSystemLocationToggle && !locationEnabled) {
                    LocationWarning(
                        onOpenSettings = {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                    )
                }

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    SecondaryButton(
                        text = "Make Discoverable",
                        modifier = Modifier.weight(1f),
                        onClick = { discoverableLauncher.launch(viewModel.discoverableIntent()) },
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        text = if (isDiscovering) "Scanning…" else "Scan for Devices",
                        enabled = !isDiscovering,
                        onClick = { viewModel.startDiscovery() },
                    )
                }

                if (allDevices.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (isDiscovering) {
                                CircularProgressIndicator(color = GreenDark)
                                Text("Looking for nearby phones…", style = MaterialTheme.typography.bodySmall, color = InkFaint, modifier = Modifier.padding(top = 12.dp))
                            } else {
                                Text(
                                    "No devices yet. Ask the other phone to also open Nearby Chat, then tap Scan.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkSoft,
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(allDevices, key = { it.address }) { device ->
                            DeviceRow(device, connectionState) { viewModel.connectTo(device.address) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = GreenDark, modifier = Modifier.size(40.dp))
        Text(
            "Bluetooth permission is needed to find nearby phones",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
        )
        PrimaryButton(text = "Allow", onClick = onRequest)
    }
}

@Composable
private fun EnableBluetoothGate(onEnable: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = GreenDark, modifier = Modifier.size(40.dp))
        Text(
            "Turn on Bluetooth to use Nearby Chat",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
        )
        PrimaryButton(text = "Turn On Bluetooth", onClick = onEnable)
    }
}

@Composable
private fun LocationWarning(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = GreenSoft),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Turn on Location to find nearby phones",
                style = MaterialTheme.typography.titleMedium,
                color = GreenDark,
            )
            Text(
                "Android requires Location to be on for Bluetooth scanning on this OS version — we never use it to track you.",
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Text(
                "Open Location Settings",
                color = GreenDark,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onOpenSettings),
            )
        }
    }
}

@Composable
private fun DeviceRow(device: NearbyDevice, connectionState: ConnectionState, onClick: () -> Unit) {
    val isConnecting = (connectionState as? ConnectionState.Connecting)?.deviceName == device.name
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isConnecting, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(GreenSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = GreenDark, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (device.isBonded) "Paired" else "Nearby",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp, color = GreenDark)
            } else if (device.isBonded) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
            }
        }
    }
}
