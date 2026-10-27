package com.zigorminsk.timemanager.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zigorminsk.timemanager.AppGraph

/** Кнопки уведомлений и будильники задач. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.init(context.applicationContext)
        val taskId = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID)
            ?: intent.getStringExtra(Notifications.EXTRA_TASK_ID)
        when (intent.action) {
            AlarmScheduler.ACTION_REMIND -> taskId?.let { ReminderEngine.fire(context, it) }
            AlarmScheduler.ACTION_DONE -> taskId?.let { ReminderEngine.complete(context, it) }
            AlarmScheduler.ACTION_SNOOZE -> taskId?.let {
                ReminderEngine.snooze(context, it, intent.getIntExtra(Notifications.EXTRA_MINUTES, 10))
            }
            AlarmScheduler.ACTION_HEARTBEAT -> ReminderEngine.heartbeat(context)
        }
    }
}
