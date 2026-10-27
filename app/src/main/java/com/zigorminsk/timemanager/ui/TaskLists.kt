package com.zigorminsk.timemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zigorminsk.timemanager.model.AppData
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.util.Conflicts
import com.zigorminsk.timemanager.util.RuTime
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun AllTasksScreen(
    data: AppData,
    now: Long,
    actions: TaskActions,
) {
    val zone = ZoneId.systemDefault()
    val active = data.tasks.filter { it.isActive }
    val overlapping = Conflicts.overlapping(active)

    val groups: List<Pair<LocalDate?, List<Task>>> = active
        .groupBy { it.dueAt?.let { RuTime.dateOf(it, zone) } }
        .toList()
        .sortedWith(compareBy({ it.first == null }, { it.first }))
        .map { it.first to it.second.sortedBy { task -> task.dueAt ?: Long.MAX_VALUE } }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (active.isEmpty()) {
            item {
                EmptyState("Задач пока нет", "Создайте первую задачу кнопкой «+»", "📝")
            }
        }
        groups.forEach { (date, tasks) ->
            item(key = "h-${date ?: "none"}") {
                SectionHeader(
                    title = date?.let { RuTime.capitalize(RuTime.dayLabel(it, RuTime.dateOf(now, zone))) } ?: "Без даты",
                    count = tasks.size,
                )
            }
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    now = now,
                    overlaps = task.id in overlapping,
                    onOpen = { actions.edit(task) },
                    onDone = { actions.done(task) },
                    onPostpone = { minutes -> actions.postpone(task, minutes) },
                    onMove = { actions.move(task) },
                    onArchive = { actions.archive(task) },
                    onDelete = { actions.delete(task) },
                )
            }
        }
    }
}

@Composable
fun ArchiveScreen(
    data: AppData,
    actions: TaskActions,
) {
    val archived = data.tasks
        .filter { it.archived }
        .sortedByDescending { it.archivedAt ?: it.doneAt ?: 0L }
    var confirmClear by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Task?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${archived.size} " + plural(archived.size, "запись", "записи", "записей") + " в архиве",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (archived.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Очистить")
                    }
                }
            }
        }

        if (archived.isEmpty()) {
            item { EmptyState("Архив пуст", "Выполненные задачи будут появляться здесь", "📦") }
        }

        items(archived, key = { "a-${it.id}" }) { task ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = true,
                        onCheckedChange = { actions.restore(task) },
                    )
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.titleSmall,
                            textDecoration = TextDecoration.LineThrough,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val doneAt = task.doneAt ?: task.archivedAt
                        Text(
                            doneAt?.let { "Выполнено ${RuTime.formatFull(it, true)}" }
                                ?: "В архиве",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    IconButton(onClick = { actions.restore(task) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Вернуть в работу", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { toDelete = task }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Очистить архив?",
            text = "Все ${archived.size} записей будут удалены безвозвратно.",
            confirmText = "Очистить",
            destructive = true,
            onConfirm = { AppGraphActions.clearArchive() },
            onDismiss = { confirmClear = false },
        )
    }

    toDelete?.let { task ->
        ConfirmDialog(
            title = "Удалить задачу?",
            text = "«${task.title}» будет удалена навсегда.",
            confirmText = "Удалить",
            destructive = true,
            onConfirm = { actions.delete(task) },
            onDismiss = { toDelete = null },
        )
    }
}

/** Небольшой мостик к репозиторию, чтобы экран не тянул контекст. */
object AppGraphActions {
    fun clearArchive() {
        com.zigorminsk.timemanager.AppGraph.repo.clearArchive()
    }
}

@Composable
fun OverlapHint(count: Int) {
    if (count <= 0) return
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "$count " + plural(count, "задача пересекается", "задачи пересекаются", "задач пересекаются") + " по времени",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
        title = { Text(title) },
        text = { Text(text) },
    )
}
