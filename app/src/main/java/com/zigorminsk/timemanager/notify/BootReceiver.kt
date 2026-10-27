package com.zigorminsk.timemanager.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.place.PlaceMonitor
import com.zigorminsk.timemanager.update.UpdateManager

/** После перезагрузки, обновления приложения или смены часового пояса всё восстанавливаем. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.init(context.applicationContext)
        runCatching { AlarmScheduler.sync(context, AppGraph.repo.data) }
        runCatching { AlarmScheduler.scheduleHeartbeat(context) }
        runCatching { UpdateManager.schedulePeriodic(context) }
        runCatching { PlaceMonitor.syncWatching(context, fromBackground = true) }
        runCatching { ReminderEngine.checkDueNow(context) }
    }
}
