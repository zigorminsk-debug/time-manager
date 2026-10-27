package com.zigorminsk.timemanager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.BuildConfig
import com.zigorminsk.timemanager.model.Settings
import com.zigorminsk.timemanager.util.CrashReporter
import com.zigorminsk.timemanager.place.PlaceMonitor
import com.zigorminsk.timemanager.update.UpdateManager
import java.io.File

@Composable
fun SettingsScreen(
    settings: Settings,
    onSettings: ((Settings) -> Settings) -> Unit,
    onCheckUpdate: () -> Unit,
    onSpeakTest: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val latestApk = remember { UpdateManager.latestApk(context) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SettingsCard("Оформление") {
                Text("Тема", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        Settings.THEME_SYSTEM to "Системная",
                        Settings.THEME_LIGHT to "Светлая",
                        Settings.THEME_DARK to "Тёмная",
                    ).forEach { (value, label) ->
                        AppFilterChip(
                            selected = settings.themeMode == value,
                            text = label,
                            modifier = Modifier.weight(1f),
                            onClick = { onSettings { it.copy(themeMode = value) } },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Размер текста: ${(settings.fontScale * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.fontScale,
                    onValueChange = { value -> onSettings { it.copy(fontScale = value) } },
                    valueRange = 0.85f..1.35f,
                    steps = 9,
                )
            }
        }

        item {
            SettingsCard("Напоминания") {
                SwitchRow(
                    "Показывать поверх экрана блокировки",
                    "Будильник открывает напоминание сразу, даже если телефон заблокирован",
                    settings.fullScreenReminder,
                ) { value -> onSettings { it.copy(fullScreenReminder = value) } }
                SwitchRow(
                    "Вибрация",
                    "Дополнительно вибрировать при напоминании",
                    settings.vibrate,
                ) { value -> onSettings { it.copy(vibrate = value) } }

                ActionRow(
                    title = "Точные будильники",
                    subtitle = if (AlarmSchedulerHelper.canExact(context)) {
                        "Разрешены — напоминания приходят минута в минуту"
                    } else {
                        "Не разрешены — напоминания могут опаздывать"
                    },
                    action = if (AlarmSchedulerHelper.canExact(context)) "Настройки" else "Разрешить",
                    onClick = { openExactAlarmSettings(context) },
                )
                ActionRow(
                    title = "Уведомления",
                    subtitle = if (notificationsEnabled(context)) "Включены" else "Выключены в системе",
                    action = "Открыть",
                    onClick = { openNotificationSettings(context) },
                )
            }
        }

        item {
            SettingsCard("Голос") {
                SwitchRow(
                    "Проговаривать напоминания",
                    "Когда срабатывает будильник, задача произносится вслух",
                    settings.speakOnReminder,
                ) { value -> onSettings { it.copy(speakOnReminder = value) } }
                Text("Скорость речи: ${"%.1f".format(settings.speechRate)}×", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.speechRate,
                    onValueChange = { value -> onSettings { it.copy(speechRate = value) } },
                    valueRange = 0.6f..1.6f,
                    steps = 9,
                )
                SecondaryButton(
                    text = "Проверить голос",
                    icon = AppIcons.Speaker,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSpeakTest,
                )
            }
        }

        item {
            SettingsCard("Напоминания по месту") {
                SwitchRow(
                    "Следить за местами в фоне",
                    "Приложение проверяет GPS/Wi-Fi и показывает баннер при прибытии",
                    settings.placeWatch,
                ) { value ->
                    onSettings { it.copy(placeWatch = value) }
                    PlaceMonitor.syncWatching(context)
                }
                ActionRow(
                    title = "Доступ к местоположению",
                    subtitle = if (PlaceMonitor.hasLocationPermission(context)) {
                        if (PlaceMonitor.hasBackgroundLocation(context)) "Разрешён всегда" else "Только при использовании приложения"
                    } else {
                        "Не разрешён — прибытие не определится"
                    },
                    action = "Настройки",
                    onClick = { openAppSettings(context) },
                )
                ActionRow(
                    title = "Энергосбережение",
                    subtitle = "На Xiaomi/Huawei отключите оптимизацию батареи и разрешите автозапуск",
                    action = "Открыть",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { onMessage("Не удалось открыть настройки батареи") }
                    },
                )
            }
        }

        item {
            SettingsCard("Обновления") {
                SwitchRow(
                    "Автообновление",
                    "Проверять новые версии и скачивать их автоматически",
                    settings.autoUpdate,
                ) { value -> onSettings { it.copy(autoUpdate = value) } }

                Text("Канал обновлений", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AppFilterChip(
                        selected = settings.updateChannel == "stable",
                        text = "Стабильные",
                        modifier = Modifier.weight(1f),
                        onClick = { onSettings { it.copy(updateChannel = "stable") } },
                    )
                    AppFilterChip(
                        selected = settings.updateChannel == "beta",
                        text = "Бета",
                        modifier = Modifier.weight(1f),
                        onClick = { onSettings { it.copy(updateChannel = "beta") } },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Проверять раз в", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(6, 12, 24).forEach { hours ->
                        AppFilterChip(
                            selected = settings.updateIntervalHours == hours,
                            text = "$hours ч",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onSettings { it.copy(updateIntervalHours = hours) }
                                UpdateManager.schedulePeriodic(context, replace = true)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                EqualButtonRow {
                    SecondaryButton(
                        text = "Проверить обновление",
                        modifier = Modifier.weight(1f),
                        onClick = onCheckUpdate,
                    )
                    if (latestApk != null) {
                        PrimaryButton(
                            text = "Установить",
                            modifier = Modifier.weight(1f),
                            onClick = { UpdateManager.install(context, latestApk) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Версия ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard("Данные") {
                EqualButtonRow {
                    SecondaryButton(
                        text = "Экспорт",
                        icon = Icons.Default.Share,
                        modifier = Modifier.weight(1f),
                        onClick = { exportTasks(context, onMessage) },
                    )
                    SecondaryButton(
                        text = "Очистить архив",
                        icon = Icons.Default.Delete,
                        modifier = Modifier.weight(1f),
                        onClick = { confirmClear = true },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Задач: ${AppGraph.repo.data.activeTasks().size}, в архиве: ${AppGraph.repo.data.archivedTasks().size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard("Диагностика") {
                ActionRow(
                    title = "Последняя ошибка",
                    subtitle = if (CrashReporter.hasCrash(context)) {
                        "Есть отчёт о сбое — откройте и отправьте его"
                    } else {
                        "Сбоев не зафиксировано"
                    },
                    action = "Показать",
                    onClick = { showReport = true },
                )
                ActionRow(
                    title = "Журнал приложения",
                    subtitle = "Технические записи для поиска проблем",
                    action = "Очистить",
                    onClick = {
                        CrashReporter.clearLog(context)
                        onMessage("Журнал очищен")
                    },
                )
            }
        }

        item {
            SettingsCard("О приложении") {
                Text(
                    "Тайм-менеджер с голосовым вводом, напоминаниями по времени и по прибытии в место. " +
                        "Данные хранятся только на телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/${UpdateManager.repo()}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Исходный код на GitHub") }
            }
        }
    }

    if (showReport) {
        CrashReportDialog(report = CrashReporter.report(context), onDismiss = { showReport = false })
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Очистить архив?",
            text = "Все выполненные задачи будут удалены безвозвратно.",
            confirmText = "Очистить",
            destructive = true,
            onConfirm = { AppGraph.repo.clearArchive() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String?, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AppTextButton(text = action, onClick = onClick)
    }
}

private fun exportTasks(context: Context, onMessage: (String) -> Unit) {
    runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "timemanager-${System.currentTimeMillis()}.json")
        file.writeText(AppGraph.repo.exportJson())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Задачи тайм-менеджера")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Экспорт задач").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { onMessage("Не удалось экспортировать задачи") }
}

fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
