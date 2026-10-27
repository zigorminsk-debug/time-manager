package com.zigorminsk.timemanager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.model.Priority
import com.zigorminsk.timemanager.model.RepeatRule
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.speech.Dictation
import com.zigorminsk.timemanager.util.Conflicts
import com.zigorminsk.timemanager.util.CrashReporter
import com.zigorminsk.timemanager.util.RuTime
import com.zigorminsk.timemanager.util.RuTimeParser

private enum class DictationTarget { TITLE, NOTE, DATETIME }

/**
 * Форма создания/редактирования задачи: полноэкранный диалог, чтобы кнопка сохранения
 * всегда была на виду и ничего не перекрывалось клавиатурой.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorSheet(
    task: Task?,
    initialPlace: Place? = null,
    places: List<Place>,
    allTasks: List<Task>,
    onDismiss: () -> Unit,
    onSave: (Task) -> Unit,
    onAddPlace: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current

    var title by remember { mutableStateOf(task?.title ?: "") }
    var note by remember { mutableStateOf(task?.note ?: "") }
    var dueAt by remember { mutableStateOf(task?.dueAt) }
    var hasTime by remember { mutableStateOf(task?.hasTime ?: true) }
    var placeId by remember { mutableStateOf(task?.placeId ?: initialPlace?.id) }
    var repeat by remember { mutableStateOf(task?.repeat ?: RepeatRule.NONE) }
    var important by remember { mutableStateOf(task?.priority == Priority.HIGH) }

    var showPicker by remember { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var confirmNoReminder by remember { mutableStateOf(false) }
    var confirmConflict by remember { mutableStateOf(false) }
    var dictationTarget by remember { mutableStateOf(DictationTarget.TITLE) }

    // Разбор написанного/надиктованного текста. Ошибка разбора не должна ронять приложение.
    val parsed = remember(title) {
        runCatching { RuTimeParser.parse(title) }
            .getOrElse {
                CrashReporter.log(context, "Не удалось разобрать текст задачи", it)
                RuTimeParser.Parsed(null, false, title, null)
            }
    }
    val conflictList = remember(dueAt, allTasks, task?.id) {
        runCatching { Conflicts.find(allTasks, dueAt, task?.id) }.getOrDefault(emptyList())
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = Dictation.extract(result.resultCode, result.data)
        if (text.isNullOrBlank()) {
            onMessage("Не удалось распознать речь. Проверьте службу распознавания Google.")
            return@rememberLauncherForActivityResult
        }
        when (dictationTarget) {
            DictationTarget.TITLE -> {
                title = text
                val p = runCatching { RuTimeParser.parse(text) }.getOrNull()
                if (p?.millis != null) {
                    dueAt = p.millis
                    hasTime = p.hasTime
                    title = p.title
                }
            }
            DictationTarget.NOTE -> {
                note = if (note.isBlank()) text else "$note. $text"
            }
            DictationTarget.DATETIME -> {
                val p = runCatching { RuTimeParser.parse(text) }.getOrNull()
                if (p?.millis == null) {
                    onMessage("Не понял дату. Скажите, например: «завтра в три часа дня»")
                } else {
                    dueAt = p.millis
                    hasTime = p.hasTime
                }
            }
        }
    }

    fun dictate(target: DictationTarget) {
        if (!Dictation.isAvailable(context)) {
            onMessage("Распознавание речи недоступно на этом устройстве")
            return
        }
        dictationTarget = target
        val prompt = when (target) {
            DictationTarget.TITLE -> "Скажите, что нужно сделать"
            DictationTarget.NOTE -> "Добавьте детали"
            DictationTarget.DATETIME -> "Скажите, когда напомнить"
        }
        runCatching { speechLauncher.launch(Dictation.intent(context, prompt)) }
            .onFailure { onMessage("Не удалось запустить распознавание речи") }
    }

    fun doSave() {
        try {
            val place = places.firstOrNull { it.id == placeId }
            val result = (task ?: Task()).copy(
                title = title.trim(),
                note = note.trim(),
                dueAt = dueAt,
                hasTime = if (dueAt == null) true else hasTime,
                placeId = placeId,
                placeName = place?.name ?: "",
                repeat = repeat,
                priority = if (important) Priority.HIGH else Priority.NORMAL,
            )
            onSave(result)
        } catch (t: Throwable) {
            CrashReporter.log(context, "Ошибка сохранения задачи", t)
            onMessage("Не удалось сохранить задачу: ${t.message ?: t::class.java.simpleName}")
        }
    }

    fun requestSave() {
        if (title.trim().isEmpty()) {
            errorText = "Напишите или надиктуйте, что нужно сделать"
            return
        }
        if (dueAt == null && placeId == null) {
            confirmNoReminder = true
            return
        }
        if (conflictList.isNotEmpty()) {
            confirmConflict = true
            return
        }
        doSave()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (task == null) "Новая задача" else "Изменить задачу",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; errorText = null },
                        label = { Text("Что нужно сделать?") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 4,
                        isError = errorText != null,
                        supportingText = errorText?.let { message ->
                            { Text(message, color = MaterialTheme.colorScheme.error) }
                        },
                        trailingIcon = {
                            IconButton(onClick = { dictate(DictationTarget.TITLE) }) {
                                Icon(AppIcons.Mic, contentDescription = "Надиктовать", tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )

                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { dictate(DictationTarget.TITLE) },
                            label = { Text("Надиктовать задачу") },
                            leadingIcon = { Icon(AppIcons.Mic, null, Modifier.size(18.dp)) },
                        )
                        AssistChip(
                            onClick = { dictate(DictationTarget.DATETIME) },
                            label = { Text("Надиктовать время") },
                            leadingIcon = { Icon(AppIcons.Mic, null, Modifier.size(18.dp)) },
                        )
                    }

                    val parsedMillis = parsed.millis
                    if (parsedMillis != null && parsed.matchedText != null && dueAt != parsedMillis) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "В тексте найдено: «${parsed.matchedText}» → " +
                                        RuTime.formatDue(parsedMillis, parsed.hasTime),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                TextButton(onClick = {
                                    dueAt = parsedMillis
                                    hasTime = parsed.hasTime
                                    title = parsed.title
                                }) { Text("Применить и убрать из названия") }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Заметка (необязательно)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                        trailingIcon = {
                            IconButton(onClick = { dictate(DictationTarget.NOTE) }) {
                                Icon(AppIcons.Mic, contentDescription = "Надиктовать заметку")
                            }
                        },
                    )

                    Spacer(Modifier.height(16.dp))
                    Text("Когда напомнить", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { showPicker = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.DateRange, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(dueAt?.let { RuTime.formatDue(it, hasTime) } ?: "Дата и время")
                        }
                        IconButton(onClick = { dictate(DictationTarget.DATETIME) }) {
                            Icon(AppIcons.Mic, contentDescription = "Надиктовать дату и время")
                        }
                        if (dueAt != null) {
                            IconButton(onClick = { dueAt = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Убрать дату")
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickOptions().forEach { (label, millis) ->
                            AssistChip(
                                onClick = { dueAt = millis; hasTime = true },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Напомнить по прибытии",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Уведомление придёт, когда телефон окажется рядом с местом (по GPS или Wi-Fi).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = placeId == null,
                            onClick = { placeId = null },
                            label = { Text("Без места") },
                        )
                        places.forEach { place ->
                            FilterChip(
                                selected = placeId == place.id,
                                onClick = { placeId = place.id },
                                label = { Text(place.name.ifBlank { "Место" }) },
                            )
                        }
                        AssistChip(onClick = onAddPlace, label = { Text("+ Место") })
                    }
                    if (placeId != null && dueAt == null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Напомним, когда приедете — время не нужно.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Повторять", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (repeat == RepeatRule.NONE) "Задача одноразовая" else repeat.label(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            OutlinedButton(onClick = { repeatMenu = true }, enabled = dueAt != null) {
                                Text(repeat.label())
                            }
                            DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                                RepeatRule.entries.forEach { rule ->
                                    DropdownMenuItem(
                                        text = { Text(rule.label()) },
                                        onClick = { repeat = rule; repeatMenu = false },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Важное напоминание", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Показать поверх экрана блокировки",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = important, onCheckedChange = { important = it })
                    }

                    if (conflictList.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Пересекается по времени: " + conflictList.joinToString(", ") {
                                    "${it.title} (${it.dueAt?.let { d -> RuTime.formatDue(d, it.hasTime) }})"
                                },
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }

                Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.background) {
                    Button(
                        onClick = { requestSave() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            if (task == null) "Создать задачу" else "Сохранить",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        DateTimePickerDialog(
            initialMillis = dueAt,
            onDismiss = { showPicker = false },
            onPicked = { millis, withTime ->
                dueAt = millis
                hasTime = withTime
                showPicker = false
            },
        )
    }

    if (confirmNoReminder) {
        AlertDialog(
            onDismissRequest = { confirmNoReminder = false },
            title = { Text("Без времени и места") },
            text = { Text("Такая задача не сможет напомнить о себе. Сохранить её как обычную заметку?") },
            confirmButton = {
                TextButton(onClick = { confirmNoReminder = false; doSave() }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { confirmNoReminder = false }) { Text("Вернуться") } },
        )
    }

    if (confirmConflict) {
        AlertDialog(
            onDismissRequest = { confirmConflict = false },
            title = { Text("Пересечение по времени") },
            text = {
                Text(
                    "На это время уже запланировано:\n" +
                        conflictList.joinToString("\n") {
                            "• ${it.title} — ${it.dueAt?.let { d -> RuTime.formatDue(d, it.hasTime) }}"
                        } + "\n\nВсё равно сохранить?",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmConflict = false; doSave() }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmConflict = false; showPicker = true }) { Text("Изменить время") }
            },
        )
    }
}
