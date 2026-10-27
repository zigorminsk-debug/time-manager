package com.zigorminsk.timemanager.notify

import android.content.Context
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.place.PlaceMonitor
import com.zigorminsk.timemanager.update.UpdateManager
import com.zigorminsk.timemanager.util.RuTime

/** Логика срабатывания напоминаний — общая для будильников, уведомлений и UI. */
object ReminderEngine {

    /** Показать напоминание о задаче (по времени или по прибытии). */
    fun fire(context: Context, taskId: String, ongoing: Boolean = false) {
        val repo = AppGraph.repo
        val settings = AppGraph.settings.value
        val task = repo.taskById(taskId)
        if (task == null || !task.isActive) {
            Notifications.cancelTask(context, taskId)
            return
        }
        val now = System.currentTimeMillis()
        if (task.snoozedUntil != null && task.snoozedUntil > now) return

        val placeTask = ongoing || (task.placeId != null && task.dueAt == null)
        Notifications.showTask(
            context = context,
            task = task,
            ongoing = placeTask,
            fullScreen = settings.fullScreenReminder && !placeTask,
        )
        repo.markNotified(taskId, now)

        if (settings.speakOnReminder) {
            val spoken = "Напоминание. " + RuTime.speakTask(task) +
                if (placeTask) ". Вы на месте." else ""
            AppGraph.speaker.speak(spoken, settings.speechRate)
        }
    }

    fun complete(context: Context, taskId: String) {
        Notifications.cancelTask(context, taskId)
        AppGraph.repo.completeTask(taskId)
        val settings = AppGraph.settings.value
        if (settings.speakOnReminder) {
            AppGraph.speaker.speak("Отлично, задача выполнена", settings.speechRate)
        }
    }

    fun snooze(context: Context, taskId: String, minutes: Int) {
        val repo = AppGraph.repo
        repo.postponeTask(taskId, minutes)
        val task = repo.taskById(taskId) ?: return
        val until = task.snoozedUntil ?: return
        val settings = AppGraph.settings.value

        // Обновляем баннер: показываем, что задача отложена
        Notifications.showTask(
            context = context,
            task = task,
            ongoing = task.placeId != null && task.dueAt == null,
            fullScreen = false,
        )
        AlarmScheduler.scheduleTask(context, task)
        if (settings.speakOnReminder) {
            AppGraph.speaker.speak("Хорошо, напомню в ${RuTime.formatTime(until)}", settings.speechRate)
        }
    }

    /** Напоминания, время которых уже наступило, но уведомление не показано. */
    fun checkDueNow(context: Context) {
        val now = System.currentTimeMillis()
        val due = AppGraph.repo.data.activeTasks().filter { task ->
            val at = task.effectiveAt() ?: return@filter false
            at <= now && task.notifiedAt == null &&
                (task.snoozedUntil == null || task.snoozedUntil <= now)
        }
        due.forEach { fire(context, it.id) }
    }

    /** Периодическая проверка (раз в 15 минут и при открытии приложения). */
    fun heartbeat(context: Context) {
        runCatching { checkDueNow(context) }
        runCatching { PlaceMonitor.checkArrivals(context) }
        runCatching { UpdateManager.maybeAutoCheck(context) }
    }
}
