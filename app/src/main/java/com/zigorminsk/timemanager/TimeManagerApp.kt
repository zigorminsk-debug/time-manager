package com.zigorminsk.timemanager

import android.app.Application
import android.content.Context
import android.util.Log
import com.zigorminsk.timemanager.data.SettingsStore
import com.zigorminsk.timemanager.data.TaskRepository
import com.zigorminsk.timemanager.notify.AlarmScheduler
import com.zigorminsk.timemanager.notify.Notifications
import com.zigorminsk.timemanager.speech.Speaker
import com.zigorminsk.timemanager.util.CrashReporter

/** Простой «граф зависимостей» приложения. */
object AppGraph {

    lateinit var context: Context
        private set
    lateinit var repo: TaskRepository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var speaker: Speaker
        private set

    @Synchronized
    fun init(appContext: Context) {
        if (::repo.isInitialized) return
        context = appContext.applicationContext
        repo = TaskRepository(context)
        settings = SettingsStore(context)
        speaker = Speaker(context)
        Notifications.createChannels(context)
        repo.onChange = { data ->
            runCatching { AlarmScheduler.sync(context, data) }
                .onFailure { Log.w("TM", "sync alarms failed", it) }
        }
    }
}

class TimeManagerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        AppGraph.init(this)
        // Восстанавливаем будильники при каждом холодном старте — это дешёвая страховка
        // от пропущенных срабатываний после перезагрузки/обновления приложения.
        runCatching {
            AlarmScheduler.sync(this, AppGraph.repo.data)
            AlarmScheduler.scheduleHeartbeat(this)
            com.zigorminsk.timemanager.update.UpdateManager.schedulePeriodic(this)
        }
    }
}
