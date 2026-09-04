package com.intelram.shield.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intelram.shield.ui.theme.Border
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.Surface

enum class NavTab(val label: String) { HOME("Home"), SCAN("Scan"), CHAT("Chat"), ALERTS("Alerts"), SETTINGS("Settings") }

@Composable
fun BottomNavBar(current: NavTab, onSelect: (NavTab) -> Unit) {
    Column {
        HorizontalDivider(color = Border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(top = 10.dp, bottom = 20.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NavTab.entries.forEach { tab ->
                val active = tab == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = when (tab) {
                            NavTab.HOME -> Icons.Filled.Home
                            NavTab.SCAN -> Icons.Filled.Search
                            NavTab.CHAT -> Icons.Filled.Bluetooth
                            NavTab.ALERTS -> Icons.Filled.Notifications
                            NavTab.SETTINGS -> Icons.Filled.Settings
                        },
                        contentDescription = tab.label,
                        tint = if (active) GreenDark else InkFaint,
                    )
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) GreenDark else InkFaint,
                    )
                }
            }
        }
    }
}
