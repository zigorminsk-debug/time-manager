package com.zigorminsk.timemanager.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.model.AppData
import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.place.PlaceMonitor

@Composable
fun PlacesScreen(
    data: AppData,
    onEditPlace: (Place) -> Unit,
    onAddPlace: () -> Unit,
    onAddTaskForPlace: (Place) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(PlaceMonitor.hasLocationPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasPermission = PlaceMonitor.hasLocationPermission(context) }
    var toDelete by remember { mutableStateOf<Place?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Как это работает", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Добавьте место (дом, работа, дача). В форме места показываются Wi-Fi сети, " +
                            "которые вещают рядом и сохранены на телефоне: отметьте нужную (или укажите координаты). " +
                            "Как только телефон окажется в зоне этой сети, придёт баннер с задачей — он будет " +
                            "висеть, пока задачу не выполнят.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!hasPermission) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Нужен доступ к местоположению, чтобы определять прибытие.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }) { Text("Разрешить") }
                    }
                }
            }
        }

        if (data.places.isEmpty()) {
            item { EmptyState("Пока нет мест", "Добавьте «Дом» и получайте напоминания по приезду", "🏠") }
        }

        items(data.places, key = { it.id }) { place ->
            val tasks = data.tasksForPlace(place.id)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            place.name.ifBlank { "Место" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onEditPlace(place) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Изменить")
                        }
                        IconButton(onClick = { toDelete = place }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (place.hasWifi) {
                        Text(
                            "Wi-Fi: " + place.wifiNames.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (place.hasGeo) {
                        Text(
                            "GPS: ${"%.5f".format(place.latitude ?: 0.0)}, ${"%.5f".format(place.longitude ?: 0.0)} · радиус ${place.radiusMeters} м",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (tasks.isEmpty()) "Задач по этому месту нет" else "Задач по месту: ${tasks.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (tasks.isNotEmpty()) {
                        tasks.forEach { task ->
                            Text(
                                "• ${task.title}" + if (task.snoozedUntil != null) " (отложена)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton(
                            text = "Добавить задачу",
                            modifier = Modifier.weight(if (place.hasGeo) 2f else 1f),
                            onClick = { onAddTaskForPlace(place) },
                        )
                        if (place.hasGeo) {
                            SmallButton(
                                text = "На карте",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("geo:${place.latitude},${place.longitude}?z=17"),
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        item {
            PrimaryButton(
                text = "Добавить место",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = onAddPlace,
            )
        }

        item {
            Text(
                "Совет: чтобы слежение работало в фоне, разрешите приложению доступ к местоположению «Всегда» " +
                    "и отключите оптимизацию батареи (Настройки → Батарея).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    toDelete?.let { place ->
        ConfirmDialog(
            title = "Удалить место?",
            text = "Задачи, привязанные к «${place.name}», останутся, но перестанут напоминать по прибытии.",
            confirmText = "Удалить",
            destructive = true,
            onConfirm = { AppGraph.repo.deletePlace(place.id) },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
fun PlaceEditorDialog(
    place: Place?,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onSave: (Place) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(place?.name ?: "") }
    var wifi by remember { mutableStateOf(place?.wifiNames?.joinToString(", ") ?: "") }
    var radius by remember { mutableStateOf((place?.radiusMeters ?: 200).toFloat()) }
    var latitude by remember { mutableStateOf(place?.latitude?.toString() ?: "") }
    var longitude by remember { mutableStateOf(place?.longitude?.toString() ?: "") }

    fun currentCoords(): Pair<Double, Double>? = PlaceMonitor.lastLocation(context)?.let { it.latitude to it.longitude }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (place == null) "Новое место" else "Изменить место") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название (Дом, Работа…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                // Сети, которые вещают прямо здесь: можно просто отметить нужные
                WifiPicker(
                    selected = wifi.split(",", ";").map { it.trim() }.filter { it.isNotBlank() },
                    onSelectedChange = { list -> wifi = list.joinToString(", ") },
                    onMessage = onMessage,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = wifi,
                    onValueChange = { wifi = it },
                    label = { Text("Wi-Fi сети (можно вписать вручную)") },
                    supportingText = {
                        Text("Через запятую. Достаточно одной сети — например, домашней.", style = MaterialTheme.typography.labelSmall)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))
                Text("Радиус GPS: ${radius.toInt()} м", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 50f..1000f,
                    steps = 18,
                )

                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it },
                    label = { Text("Широта") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it },
                    label = { Text("Долгота") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextButton(onClick = {
                    val coords = currentCoords()
                    if (coords == null) {
                        onMessage("Не удалось получить координаты. Проверьте GPS и разрешение.")
                    } else {
                        latitude = "%.6f".format(coords.first)
                        longitude = "%.6f".format(coords.second)
                    }
                }) { Text("Взять моё текущее положение") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val lat = latitude.replace(',', '.').toDoubleOrNull()
                val lon = longitude.replace(',', '.').toDoubleOrNull()
                val wifiList = wifi.split(",", ";")
                    .map { it.trim().trim('"') }
                    .filter { it.isNotBlank() }
                if (name.isBlank()) {
                    onMessage("Придумайте название месту")
                    return@TextButton
                }
                if (wifiList.isEmpty() && (lat == null || lon == null)) {
                    onMessage("Укажите Wi-Fi сеть или координаты места")
                    return@TextButton
                }
                onSave(
                    (place ?: Place()).copy(
                        name = name.trim(),
                        wifiNames = wifiList,
                        radiusMeters = radius.toInt(),
                        latitude = lat,
                        longitude = lon,
                    ),
                )
            }) { Text("Сохранить") }
        },
        dismissButton = { AppTextButton(text = "Отмена", onClick = onDismiss) },
    )
}
