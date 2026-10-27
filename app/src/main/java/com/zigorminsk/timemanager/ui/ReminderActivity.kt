package com.zigorminsk.timemanager.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.notify.Notifications
import com.zigorminsk.timemanager.notify.ReminderEngine
import com.zigorminsk.timemanager.util.RuTime

class ReminderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        val taskId = intent?.getStringExtra(Notifications.EXTRA_TASK_ID)
        setContent {
            val settings by AppGraph.settings.state.collectAsState()
            TimeManagerTheme(themeMode = settings.themeMode, fontScale = settings.fontScale) {
                ReminderScreen(taskId = taskId, onClose = { finish() })
            }
        }
    }
}

@Composable
fun ReminderScreen(taskId: String?, onClose: () -> Unit) {
    val data by AppGraph.repo.state.collectAsState()
    val task: Task? = data.tasks.firstOrNull { it.id == taskId }
    val now = System.currentTimeMillis()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        AdaptiveScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                "НАПОМИНАНИЕ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                task?.title ?: "Задача уже выполнена",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            task?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DateRange,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.dueAt?.let { RuTime.formatFull(it, t.hasTime) } ?: "По прибытии",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (t.placeId != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Вы на месте: ${t.placeName.ifBlank { "место" }}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (t.note.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(t.note, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (t.overdueText(now) != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        t.overdueText(now)!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            Text("Отложить на", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(10, 15, 20, 30).forEach { minutes ->
                    FilledTonalButton(
                        onClick = {
                            task?.let { ReminderEngine.snooze(applicationContextForAction(), it.id, minutes) }
                            onClose()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).heightIn(min = Dims.Button),
                    ) {
                        Text(
                            "$minutes",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("минут", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    task?.let { ReminderEngine.complete(applicationContextForAction(), it.id) }
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Выполнено", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(8.dp))
            EqualButtonRow {
                SecondaryButton(
                    text = "Открыть задачу",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val context = applicationContextForAction()
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            taskId?.let { putExtra(Notifications.EXTRA_TASK_ID, it) }
                        }
                        context.startActivity(intent)
                        onClose()
                    },
                )
                SecondaryButton(
                    text = "Закрыть",
                    modifier = Modifier.weight(1f),
                    onClick = onClose,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
}

private fun applicationContextForAction() = AppGraph.context

private fun Task.overdueText(now: Long): String? {
    val due = dueAt ?: return null
    if (due >= now) return null
    val minutes = (now - due) / 60_000
    val text = when {
        minutes < 60 -> "$minutes ${if (minutes % 10 == 1L && minutes != 11L) "минуту" else "минут"} назад"
        else -> "${minutes / 60} ч назад"
    }
    return "План был на ${RuTime.formatTime(due)} — $text"
}
