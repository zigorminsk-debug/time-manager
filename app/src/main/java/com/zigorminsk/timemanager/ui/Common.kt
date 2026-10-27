package com.zigorminsk.timemanager.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.util.RuTime
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** «Текущее время» с автообновлением — чтобы просрочки подсвечивались сами. */
@Composable
fun rememberNow(intervalMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun MetaChip(
    text: String,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = background,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}

@Composable
fun SectionHeader(title: String, count: Int? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String? = null, emoji: String = "🗒️") {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * Карточка задачи со всеми действиями.
 */
@Composable
fun TaskCard(
    task: Task,
    now: Long,
    showCheckbox: Boolean = true,
    overlaps: Boolean = false,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onPostpone: (Int) -> Unit,
    onMove: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var postponeMenu by remember { mutableStateOf(false) }

    val overdue = task.isActive && task.dueAt != null && task.dueAt < now
    val snoozedUntil = task.snoozedUntil?.takeIf { it > now }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (showCheckbox) {
                Checkbox(
                    checked = task.done,
                    onCheckedChange = { if (it) onDone() },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary),
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }

            Column(
                modifier = Modifier.weight(1f).clickable { onOpen() },
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (task.note.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        task.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    task.dueAt?.let { due ->
                        MetaChip(
                            text = RuTime.formatDue(due, task.hasTime, now),
                            icon = Icons.Default.DateRange,
                            tint = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            background = if (overdue) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            },
                        )
                    }
                    if (task.placeId != null) {
                        MetaChip(
                            text = task.placeName.ifBlank { "по месту" },
                            icon = Icons.Default.Place,
                            tint = MaterialTheme.colorScheme.secondary,
                            background = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                        )
                    }
                    if (snoozedUntil != null) {
                        MetaChip(
                            text = "отложено до ${RuTime.formatTime(snoozedUntil)}",
                            icon = Icons.Default.Refresh,
                            tint = MaterialTheme.colorScheme.tertiary,
                            background = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                        )
                    }
                    if (overlaps) {
                        MetaChip(
                            text = "пересечение",
                            icon = Icons.Default.Warning,
                            tint = MaterialTheme.colorScheme.error,
                            background = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Действия")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Изменить") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Перенести на другое время") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(
                        text = { Text("Отложить") },
                        onClick = { menu = false; postponeMenu = true },
                    )
                    if (task.isActive) {
                        DropdownMenuItem(text = { Text("В архив") }, onClick = { menu = false; onArchive() })
                    }
                    DropdownMenuItem(
                        text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    )
                }
                DropdownMenu(expanded = postponeMenu, onDismissRequest = { postponeMenu = false }) {
                    listOf(10, 15, 20, 30, 60).forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(if (minutes == 60) "На 1 час" else "На $minutes минут") },
                            onClick = { postponeMenu = false; onPostpone(minutes) },
                        )
                    }
                }
            }
        }
    }
}

/** Выбор даты (и опционально времени). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(
    initialMillis: Long?,
    onDismiss: () -> Unit,
    onPicked: (millis: Long, hasTime: Boolean) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initialZdt: ZonedDateTime = Instant.ofEpochMilli(initialMillis ?: System.currentTimeMillis()).atZone(zone)
    var step by remember { mutableStateOf(0) }
    var pickedDate by remember { mutableStateOf(initialZdt.toLocalDate()) }

    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initialZdt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val timeState = rememberTimePickerState(
        initialHour = initialZdt.hour,
        initialMinute = initialZdt.minute,
        is24Hour = true,
    )

    when (step) {
        0 -> DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val selected = dateState.selectedDateMillis
                        ?: pickedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    pickedDate = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                    step = 1
                }) { Text("Далее") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val selected = dateState.selectedDateMillis
                    if (selected != null) {
                        val date = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                        onPicked(RuTime.combine(date, 9, 0), false)
                    } else {
                        onDismiss()
                    }
                }) { Text("Без времени") }
            },
        ) {
            DatePicker(state = dateState)
        }

        1 -> Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp)) {
                    Text("Время", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    TimePicker(state = timeState)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Отмена") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            onPicked(RuTime.combine(pickedDate, timeState.hour, timeState.minute), true)
                        }) { Text("Готово") }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "Да",
    dismissText: String = "Отмена",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

/** Диалог «перенести задачу». */
@Composable
fun MoveTaskDialog(
    task: Task,
    onDismiss: () -> Unit,
    onMoved: (millis: Long, hasTime: Boolean) -> Unit,
) {
    var picker by remember { mutableStateOf(false) }
    if (!picker) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Перенести задачу") },
            text = {
                Column {
                    Text("Выберите новое время для «${task.title}».", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    val quick = quickOptions()
                    quick.forEach { (label, millis) ->
                        TextButton(
                            onClick = { onMoved(millis, true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = true }) { Text("Другое время") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        )
    } else {
        DateTimePickerDialog(
            initialMillis = task.dueAt,
            onDismiss = onDismiss,
            onPicked = { millis, hasTime -> onMoved(millis, hasTime) },
        )
    }
}

/** Быстрые варианты времени: «через час», «сегодня вечером», «завтра утром». */
@Composable
fun quickOptions(): List<Pair<String, Long>> {
    val zone = ZoneId.systemDefault()
    val today: LocalDate = RuTime.today(zone)
    val now = System.currentTimeMillis()
    return listOf(
        "Через 30 минут" to now + 30 * 60_000L,
        "Через час" to now + 60 * 60_000L,
        "Через 2 часа" to now + 120 * 60_000L,
        "Сегодня вечером, 19:00" to if (RuTime.combine(today, 19, 0) > now) {
            RuTime.combine(today, 19, 0)
        } else {
            RuTime.combine(today.plusDays(1), 19, 0)
        },
        "Завтра утром, 09:00" to RuTime.combine(today.plusDays(1), 9, 0),
    )
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** Диалог с текстом сбоя: копирование/отправка отчёта. */
@Composable
fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text("Приложение было остановлено", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Этот текст поможет найти причину сбоя. Скопируйте его или отправьте разработчику.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        report,
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(report))
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    }) { Text("Копировать") }
                    TextButton(onClick = { shareText(context, report) }) { Text("Отправить") }
                    Button(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
}

fun shareText(context: android.content.Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Отчёт о сбое — Тайм-менеджер")
        }
        context.startActivity(
            Intent.createChooser(send, "Отправить отчёт").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
