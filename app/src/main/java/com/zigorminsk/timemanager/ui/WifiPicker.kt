package com.zigorminsk.timemanager.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zigorminsk.timemanager.place.WifiNetwork
import com.zigorminsk.timemanager.place.WifiScanner
import com.zigorminsk.timemanager.util.WifiMatcher
import kotlinx.coroutines.delay

/**
 * Список сетей Wi-Fi, которые вещают рядом: подключённая, сохранённые на телефоне
 * и просто видимые. Отмеченные сети сохраняются в настройках места — при попадании
 * в зону их вещания придёт напоминание.
 */
@Composable
fun WifiPicker(
    selected: List<String>,
    onSelectedChange: (List<String>) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var networks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(WifiScanner.hasPermission(context)) }
    var scanning by remember { mutableStateOf(false) }
    var wifiEnabled by remember { mutableStateOf(WifiScanner.isWifiEnabled(context)) }
    var refreshToken by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermission = WifiScanner.hasPermission(context)
        if (!hasPermission) {
            onMessage("Без доступа к Wi-Fi список сетей недоступен. Сеть можно вписать вручную.")
        } else if (result.values.any { it }) {
            refreshToken++
        }
    }

    LaunchedEffect(refreshToken) {
        if (!hasPermission) {
            networks = emptyList()
            return@LaunchedEffect
        }
        scanning = true
        // Первое чтение — сразу, второе — после короткой паузы: система успевает обновить скан
        networks = WifiScanner.scan(context, requestNewScan = true)
        delay(1500)
        networks = WifiScanner.scan(context, requestNewScan = false)
        wifiEnabled = WifiScanner.isWifiEnabled(context)
        scanning = false
    }

    fun toggle(network: WifiNetwork) {
        val key = network.key
        if (key.isBlank()) return
        val currently = selected.any { WifiMatcher.normalize(it) == WifiMatcher.normalize(key) }
        val next = if (currently) {
            selected.filterNot { WifiMatcher.normalize(it) == WifiMatcher.normalize(key) }
        } else {
            selected + key
        }
        onSelectedChange(next)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Сети Wi-Fi рядом",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (hasPermission) {
                SquareIconButton(
                    icon = Icons.Default.Refresh,
                    description = "Обновить список сетей",
                    onClick = { refreshToken++ },
                )
            }
        }

        when {
            !hasPermission -> {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Чтобы показать сети рядом, нужно разрешение на геолокацию и Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        SecondaryButton(
                            text = "Показать сети рядом",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val permissions = buildList {
                                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                    }
                                }
                                permissionLauncher.launch(permissions.toTypedArray())
                            },
                        )
                    }
                }
            }

            !wifiEnabled -> {
                Text(
                    "Wi-Fi выключен — включите его, чтобы увидеть сети.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            scanning && networks.isEmpty() -> {
                Text(
                    "Ищу сети…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            networks.isEmpty() -> {
                Text(
                    "Сети не найдены. Wi-Fi может быть выключен или сканирование ограничено системой.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    networks.forEach { network ->
                        NetworkRow(
                            network = network,
                            checked = selected.any {
                                WifiMatcher.normalize(it) == WifiMatcher.normalize(network.key)
                            },
                            onClick = { toggle(network) },
                        )
                    }
                }
            }
        }

        // Выбранные сети могут быть и не видны сейчас (например, дома выберете рабочую сеть)
        val hiddenSelected = selected.filter { name ->
            networks.none { WifiMatcher.normalize(it.key) == WifiMatcher.normalize(name) }
        }
        if (hiddenSelected.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Выбрано вручную: " + hiddenSelected.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Напомним, когда телефон окажется в зоне этой сети.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun NetworkRow(network: WifiNetwork, checked: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (checked) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
            Column(Modifier.weight(1f)) {
                Text(
                    network.ssid.ifBlank { network.bssid ?: "Скрытая сеть" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (network.connected) Badge("подключена", MaterialTheme.colorScheme.secondary)
                    if (network.saved && !network.connected) Badge("сохранена", MaterialTheme.colorScheme.primary)
                    if (network.secured) Badge("защищена", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SignalBars(level = network.level)
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** Полоски уровня сигнала: 1…4 шт. */
@Composable
private fun SignalBars(level: Int) {
    val filled = level.coerceIn(1, 4)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(4) { index ->
            val height = (6 + index * 4).dp
            Box(
                Modifier
                    .width(4.dp)
                    .height(height)
                    .background(
                        if (index < filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    ),
            )
        }
        if (filled >= 4) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
