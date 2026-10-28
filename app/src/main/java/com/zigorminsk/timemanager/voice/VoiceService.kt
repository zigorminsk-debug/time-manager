package com.zigorminsk.timemanager.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-сервис «без рук»: микрофон слушает фразу-обращение, пока приложение
 * свёрнуто. Показывает тихое уведомление с кнопкой «Выключить».
 *
 * Android разрешает такой сервис, только если пользователь сам его включил
 * (переключатель в настройках), а разрешение на микрофон уже выдано.
 */
class VoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!VoiceEngine.hasMicrophone(this)) {
            Log.w(TAG, "нет разрешения на микрофон — сервис не запускается")
            stopSelf()
            return START_NOT_STICKY
        }
        return runCatching {
            startAsForeground()
            VoiceEngine.onServiceStarted(this)
            observeState()
            START_STICKY
        }.getOrElse {
            Log.w(TAG, "не удалось выйти в foreground", it)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startAsForeground() {
        val notification = Notifications.voiceServiceNotification(
            context = this,
            title = "Голосовой помощник включён",
            text = "Скажите «${AppGraph.settings.value.wakePhrase}…», чтобы дать команду",
            listening = true,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_VOICE_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(Notifications.ID_VOICE_SERVICE, notification)
        }
    }

    private fun observeState() {
        observer?.cancel()
        observer = scope.launch {
            VoiceEngine.state.collect { state ->
                val title = when (state.phase) {
                    VoicePhase.WAKE -> "Слушаю фразу «${state.wakePhrase}»"
                    VoicePhase.LISTENING -> "Слушаю команду"
                    VoicePhase.THINKING -> "Разбираю команду"
                    VoicePhase.SPEAKING -> "Отвечаю"
                    VoicePhase.ERROR -> "Голосовой помощник: нужен интернет"
                    VoicePhase.IDLE -> "Голосовой помощник включён"
                }
                val text = when {
                    state.partial.isNotBlank() -> state.partial
                    state.error != null -> state.error
                    state.lastReply.isNotBlank() -> state.lastReply
                    else -> "Активных задач: ${AppGraph.repo.data.activeTasks().size}"
                }
                Notifications.showVoiceService(this@VoiceService, title, text, state.listening)
            }
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        VoiceEngine.onServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TM/VoiceService"

        const val ACTION_START = "com.zigorminsk.timemanager.action.START_VOICE"
        const val ACTION_STOP = "com.zigorminsk.timemanager.action.STOP_VOICE"

        /** Запустить прослушивание фразы-обращения (только из видимого экрана). */
        fun start(context: Context) {
            if (!VoiceEngine.hasMicrophone(context)) return
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_START)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.w(TAG, "start failed", it)
            }
        }

        /** Выключить: останавливаем сервис и движок. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceService::class.java)) }
            runCatching { VoiceEngine.onServiceStopped() }
        }
    }
}
