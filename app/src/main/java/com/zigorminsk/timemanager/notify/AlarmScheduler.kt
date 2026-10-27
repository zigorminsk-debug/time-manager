package com.zigorminsk.timemanager.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.zigorminsk.timemanager.model.AppData
import com.zigorminsk.timemanager.model.Task

/** Планирование точных будильников на время задачи. */
object AlarmScheduler {

    const val ACTION_REMIND = "com.zigorminsk.timemanager.action.REMIND"
    const val ACTION_DONE = "com.zigorminsk.timemanager.action.DONE"
    const val ACTION_SNOOZE = "com.zigorminsk.timemanager.action.SNOOZE"
    const val ACTION_HEARTBEAT = "com.zigorminsk.timemanager.action.HEARTBEAT"

    const val EXTRA_TASK_ID = "task_id"

    private const val HEARTBEAT_REQUEST = 42_424
    private const val HEARTBEAT_INTERVAL = 15 * 60 * 1000L

    /** Пересобрать все будильники под текущее состояние задач. */
    fun sync(context: Context, data: AppData) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()

        for (task in data.tasks) {
            runCatching { am.cancel(remindIntent(context, task.id)) }
        }

        for (task in data.activeTasks()) {
            val at = task.effectiveAt() ?: continue
            if (task.snoozedUntil != null && task.snoozedUntil > now) {
                // Отложенная задача: будильник на конец отсрочки
                setExact(context, task.snoozedUntil, remindIntent(context, task.id))
                continue
            }
            if (at > now) {
                setExact(context, at, remindIntent(context, task.id))
            } else if (task.notifiedAt == null) {
                // Просроченная и ещё не показанная задача — напомним сразу
                setExact(context, now + 3_000, remindIntent(context, task.id))
            }
        }
    }

    fun scheduleTask(context: Context, task: Task) {
        val at = task.effectiveAt() ?: return
        if (!task.isActive) return
        setExact(context, maxOf(at, System.currentTimeMillis() + 2_000), remindIntent(context, task.id))
    }

    fun cancelTask(context: Context, taskId: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(remindIntent(context, taskId)) }
    }

    /** Раз в 15 минут — страховка: проверяем просроченные задачи, места и обновления. */
    fun scheduleHeartbeat(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_HEARTBEAT
            data = Uri.parse("tm://heartbeat")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            HEARTBEAT_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + HEARTBEAT_INTERVAL,
                HEARTBEAT_INTERVAL,
                pi,
            )
        }
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun setExact(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            Log.w("TM", "exact alarm not allowed, fallback", e)
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    private fun remindIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            data = Uri.parse("tm://remind/$taskId")
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
