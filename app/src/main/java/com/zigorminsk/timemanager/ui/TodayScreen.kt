package com.zigorminsk.timemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zigorminsk.timemanager.model.AppData
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.util.Conflicts
import com.zigorminsk.timemanager.util.RuTime
import java.time.ZoneId

@Composable
fun TodayScreen(
    data: AppData,
    now: Long,
    speaking: Boolean,
    actions: TaskActions,
    onSpeakDay: () -> Unit,
    onStopSpeaking: () -> Unit,
    onVoiceAssistant: () -> Unit = {},
    showVoiceButton: Boolean = true,
    header: @Composable () -> Unit = {},
) {
    val zone = ZoneId.systemDefault()
    val today = RuTime.dateOf(now, zone)
    val endOfDay = RuTime.combine(today.plusDays(1), 0, 0)
    val active = data.tasks.filter { it.isActive }
    val overlapping = Conflicts.overlapping(active)

    val overdue = active.filter { it.dueAt != null && it.dueAt < now }.sortedBy { it.dueAt }
    val todayLater = active.filter { it.dueAt != null && it.dueAt >= now && it.dueAt < endOfDay }.sortedBy { it.dueAt }
    val placeTasks = active.filter { it.placeId != null && it.dueAt == null }
    val upcoming = active.filter { it.dueAt != null && it.dueAt >= endOfDay }.sortedBy { it.dueAt }.take(5)

    val weekday = RuTime.weekdayName(today)
    val totalToday = overdue.size + todayLater.size + placeTasks.size

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { header() }

        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "${weekday.replaceFirstChar { it.uppercase() }}, ${today.dayOfMonth} " +
                            RuTime.MONTHS_GEN[today.monthValue - 1],
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            totalToday == 0 -> "Свободный день"
                            else -> "$totalToday " + plural(totalToday, "задача", "задачи", "задач") +
                                if (overdue.isNotEmpty()) ", ${overdue.size} просрочено" else ""
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        text = if (speaking) "Остановить" else "Проговорить день",
                        icon = if (speaking) AppIcons.Stop else AppIcons.Speaker,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (speaking) onStopSpeaking() else onSpeakDay() },
                    )
                    if (showVoiceButton) {
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(
                            text = "Спросить голосом",
                            icon = AppIcons.Mic,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onVoiceAssistant,
                        )
                    }
                }
            }
        }

        if (overdue.isNotEmpty()) {
            item { SectionHeader("Просрочено", overdue.size) }
            items(overdue, key = { "o-${it.id}" }) { task ->
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

        if (todayLater.isNotEmpty()) {
            item { SectionHeader("Сегодня", todayLater.size) }
            items(todayLater, key = { "t-${it.id}" }) { task ->
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

        if (placeTasks.isNotEmpty()) {
            item { SectionHeader("Напоминания по месту", placeTasks.size) }
            items(placeTasks, key = { "p-${it.id}" }) { task ->
                TaskCard(
                    task = task,
                    now = now,
                    onOpen = { actions.edit(task) },
                    onDone = { actions.done(task) },
                    onPostpone = { minutes -> actions.postpone(task, minutes) },
                    onMove = { actions.move(task) },
                    onArchive = { actions.archive(task) },
                    onDelete = { actions.delete(task) },
                )
            }
        }

        if (upcoming.isNotEmpty()) {
            item { SectionHeader("Ближайшие", upcoming.size) }
            items(upcoming, key = { "u-${it.id}" }) { task ->
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

        if (totalToday == 0 && upcoming.isEmpty()) {
            item {
                EmptyState(
                    title = "На сегодня задач нет",
                    subtitle = "Добавьте задачу кнопкой «+» или надиктуйте её голосом",
                    emoji = "🎉",
                )
            }
        }
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

/** Набор действий над задачей, чтобы не тащить шесть лямбд в каждую карточку. */
class TaskActions(
    val edit: (Task) -> Unit,
    val done: (Task) -> Unit,
    val postpone: (Task, Int) -> Unit,
    val move: (Task) -> Unit,
    val archive: (Task) -> Unit,
    val delete: (Task) -> Unit,
    val restore: (Task) -> Unit = {},
)
