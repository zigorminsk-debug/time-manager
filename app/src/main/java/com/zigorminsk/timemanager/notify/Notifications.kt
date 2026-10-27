package com.zigorminsk.timemanager.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zigorminsk.timemanager.R
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.ui.MainActivity
import com.zigorminsk.timemanager.ui.ReminderActivity
import com.zigorminsk.timemanager.util.RuTime

object Notifications {

    const val CH_REMINDERS = "reminders"
    const val CH_PLACE = "place"
    const val CH_SERVICE = "place_service"
    const val CH_UPDATES = "updates"

    const val ID_SERVICE = 900_001
    const val ID_UPDATE_AVAILABLE = 900_002
    const val ID_UPDATE_READY = 900_003

    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_MINUTES = "minutes"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val reminders = NotificationChannel(
            CH_REMINDERS, "Напоминания", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Напоминания о задачах"
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val place = NotificationChannel(
            CH_PLACE, "Напоминания по месту", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Всплывают при прибытии в место и висят, пока не выполнены"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        val service = NotificationChannel(
            CH_SERVICE, "Слежение за местами", NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Небольшое уведомление, пока приложение следит за GPS/Wi-Fi"
            setShowBadge(false)
        }

        val updates = NotificationChannel(
            CH_UPDATES, "Обновления", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Новые версии приложения"
        }

        nm.createNotificationChannels(listOf(reminders, place, service, updates))
    }

    fun taskNotificationId(taskId: String): Int = 10_000 + (taskId.hashCode() and 0x0FFFFFFF)

    fun cancelTask(context: Context, taskId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(taskNotificationId(taskId)) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /** Уведомление о задаче. Для задач «по месту» — постоянный баннер. */
    fun showTask(
        context: Context,
        task: Task,
        ongoing: Boolean,
        fullScreen: Boolean,
        speakText: String? = null,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val now = System.currentTimeMillis()
        val dueText = when {
            task.dueAt != null -> RuTime.formatDue(task.dueAt, task.hasTime, now) +
                if (task.hasTime) "" else " (в течение дня)"
            task.placeId != null -> "По прибытии: ${task.placeName.ifBlank { "место" }}"
            else -> "Без времени"
        }
        val overdue = task.dueAt != null && task.dueAt < now
        val snoozed = task.snoozedUntil?.takeIf { it > now }

        val text = buildString {
            append(dueText)
            if (task.placeId != null && task.dueAt != null) {
                append(" · приеду в «").append(task.placeName.ifBlank { "место" }).append("»")
            }
            if (snoozed != null) {
                append(" · отложено до ").append(RuTime.formatTime(snoozed))
            }
            if (task.note.isNotBlank()) {
                append("\n").append(task.note)
            }
            if (overdue) append("\nПросрочено")
        }

        val builder = NotificationCompat.Builder(context, if (ongoing) CH_PLACE else CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title.ifBlank { "Напоминание" })
            .setContentText(dueText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setWhen(task.dueAt ?: now)
            .setShowWhen(task.dueAt != null)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF4550E5.toInt())
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(ongoing)
            .setContentIntent(openTask(context, task.id, task.id.hashCode()))
            .addAction(R.drawable.ic_done, "Выполнено", action(context, task.id, AlarmScheduler.ACTION_DONE))
            .addAction(R.drawable.ic_snooze, "10 мин", snooze(context, task.id, 10))
            .addAction(R.drawable.ic_snooze, "30 мин", snooze(context, task.id, 30))
            .addAction(R.drawable.ic_open, "Отложить…", openReminder(context, task.id))

        if (fullScreen) {
            runCatching { builder.setFullScreenIntent(openReminder(context, task.id), true) }
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(taskNotificationId(task.id), builder.build())
        }
    }

    fun showPlaceService(context: Context, places: List<String>) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val text = if (places.isEmpty()) "Слежение включено" else "Жду прибытия: ${places.joinToString(", ")}"
        val builder = NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_place_small)
            .setContentTitle("Слежение за местами включено")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openPlaces(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        runCatching { nm.notify(ID_SERVICE, builder.build()) }
    }

    fun serviceNotification(context: Context, places: List<String>): Notification {
        val text = if (places.isEmpty()) "Слежение включено" else "Жду прибытия: ${places.joinToString(", ")}"
        return NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_place_small)
            .setContentTitle("Слежение за местами включено")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openPlaces(context))
            .build()
    }

    fun showUpdateAvailable(context: Context, version: String, notes: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val download = PendingIntent.getBroadcast(
            context,
            910_001,
            Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_DOWNLOAD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Доступно обновление $version")
            .setContentText(notes.ifBlank { "Нажмите, чтобы скачать и установить" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes.ifBlank { "Нажмите, чтобы скачать и установить" }))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(0xFF4550E5.toInt())
            .setContentIntent(download)
            .addAction(R.drawable.ic_open, "Скачать", download)
        runCatching { NotificationManagerCompat.from(context).notify(ID_UPDATE_AVAILABLE, builder.build()) }
    }

    fun showUpdateReady(context: Context, version: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val install = PendingIntent.getBroadcast(
            context,
            910_002,
            Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_INSTALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Обновление $version скачано")
            .setContentText("Нажмите «Установить», чтобы обновиться поверх текущей версии")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF4550E5.toInt())
            .setContentIntent(install)
            .addAction(R.drawable.ic_open, "Установить", install)
        runCatching { NotificationManagerCompat.from(context).notify(ID_UPDATE_READY, builder.build()) }
    }

    // ------------------------------------------------------------ PendingIntent-ы

    private fun action(context: Context, taskId: String, action: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("tm://task/$taskId/$action")
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            (taskId + action).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun snooze(context: Context, taskId: String, minutes: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_SNOOZE
            data = Uri.parse("tm://task/$taskId/snooze/$minutes")
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_MINUTES, minutes)
        }
        return PendingIntent.getBroadcast(
            context,
            (taskId + "snooze" + minutes).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openReminder(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ReminderActivity::class.java).apply {
            data = Uri.parse("tm://task/$taskId/reminder")
            putExtra(EXTRA_TASK_ID, taskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            (taskId + "reminder").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openTask(context: Context, taskId: String, seed: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getActivity(
            context,
            seed,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openPlaces(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "places")
        }
        return PendingIntent.getActivity(context, 900_010, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Атрибуты звука не нужны — канал сам управляет звуком. */
    @Suppress("unused")
    private val silentAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .build()
}
