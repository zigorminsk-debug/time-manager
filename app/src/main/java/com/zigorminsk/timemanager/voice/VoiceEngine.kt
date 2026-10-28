package com.zigorminsk.timemanager.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.util.CrashReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoicePhase { IDLE, WAKE, LISTENING, THINKING, SPEAKING, ERROR }

/** Реплика в истории диалога. */
data class VoiceLine(
    val fromUser: Boolean,
    val text: String,
    val at: Long = System.currentTimeMillis(),
)

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val handsFree: Boolean = false,
    /** Частичный результат распознавания — «живая» расшифровка. */
    val partial: String = "",
    val amplitude: Float = 0f,
    val error: String? = null,
    val history: List<VoiceLine> = emptyList(),
    val hints: List<String> = emptyList(),
    val wakePhrase: String = WakeWord.DEFAULT,
    val listening: Boolean = false,
) {
    val lastUserLine: String get() = history.lastOrNull { it.fromUser }?.text ?: ""
    val lastReply: String get() = history.lastOrNull { !it.fromUser }?.text ?: ""

    val statusLabel: String
        get() = when (phase) {
            VoicePhase.IDLE -> "Готов слушать"
            VoicePhase.WAKE -> "Жду «${wakePhrase.replaceFirstChar { it.uppercase() }}»"
            VoicePhase.LISTENING -> if (partial.isBlank()) "Слушаю…" else "Слушаю…"
            VoicePhase.THINKING -> "Думаю…"
            VoicePhase.SPEAKING -> "Говорю…"
            VoicePhase.ERROR -> "Не получилось"
        }

    val hintLine: String
        get() = when (phase) {
            VoicePhase.IDLE -> "Нажмите на микрофон и скажите команду"
            VoicePhase.WAKE -> "Скажите: «${wakePhrase.replaceFirstChar { it.uppercase() }}, что на сегодня»"
            VoicePhase.LISTENING -> "Например: «напомни позвонить маме завтра в девять»"
            VoicePhase.THINKING -> "Разбираю сказанное"
            VoicePhase.SPEAKING -> "Можно перебить кнопкой «Стоп»"
            VoicePhase.ERROR -> error ?: "Попробуйте ещё раз"
        }
}

/**
 * Живой голосовой движок: слушает микрофон, ловит фразу-обращение,
 * показывает распознавание в реальном времени, отвечает голосом и снова
 * слушает — как Алиса, но про задачи.
 *
 * Все методы вызываются с главного потока (UI/сервис).
 */
object VoiceEngine {

    private const val TAG = "TM/Voice"

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var recognizer: SpeechRecognizer? = null
    private var pendingTask: Runnable? = null

    private var active = false
    private var uiVisible = false
    private var handsFree = false
    private var awaitingWake = false
    private var capturing = false
    private var misses = 0
    private var followUps = 0
    private var failures = 0
    private var generation = 0

    private val defaultHints = listOf("Что на сегодня?", "Напомни купить хлеб", "Что ты умеешь?")

    // ------------------------------------------------------------------ публичное

    fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isActive(): Boolean = active

    /** Открыть экран ассистента. */
    fun open(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        uiVisible = true
        if (!hasMicrophone(ctx)) {
            _state.value = _state.value.copy(
                phase = VoicePhase.ERROR,
                error = "Разрешите доступ к микрофону — без него я не услышу команду.",
                listening = false,
            )
            return
        }
        if (!isAvailable(ctx)) {
            _state.value = _state.value.copy(
                phase = VoicePhase.ERROR,
                error = "На устройстве нет службы распознавания речи. Установите «Google» или аналог.",
                listening = false,
            )
            return
        }
        if (active) {
            // Экран открыт — слушаем команду сразу, без фразы-обращения.
            if (handsFree && awaitingWake) {
                listenCommand()
            } else if (!capturing && _state.value.phase != VoicePhase.SPEAKING &&
                _state.value.phase != VoicePhase.THINKING
            ) {
                listenCommand()
            }
            return
        }
        beginSession(ctx)
        if (handsFree) listenWake() else listenCommand()
    }

    /** Экран закрыт: в режиме «без рук» продолжаем ждать фразу, иначе выключаемся. */
    fun close() {
        uiVisible = false
        if (!active) return
        if (handsFree) {
            stopCapture()
            listenWake()
        } else {
            shutdown()
        }
    }

    fun shutdown() {
        cancelScheduled()
        stopCapture()
        runCatching { AppGraph.speaker.stop() }
        active = false
        awaitingWake = false
        misses = 0
        followUps = 0
        failures = 0
        _state.value = _state.value.copy(
            phase = VoicePhase.IDLE,
            partial = "",
            amplitude = 0f,
            listening = false,
        )
    }

    /** Кнопка «микрофон»: слушать одну команду. */
    fun pushToTalk(context: Context) {
        appContext = context.applicationContext
        uiVisible = true
        if (!active) {
            open(context)
            return
        }
        if (!hasMicrophone(context)) {
            _state.value = _state.value.copy(
                phase = VoicePhase.ERROR,
                error = "Разрешите доступ к микрофону.",
            )
            return
        }
        runCatching { AppGraph.speaker.stop() }
        misses = 0
        followUps = AppGraph.settings.value.voiceFollowUps
        listenCommand()
    }

    /** Перебить ассистента: замолчать и снова слушать. */
    fun interrupt() {
        runCatching { AppGraph.speaker.stop() }
        if (!active) return
        if (handsFree && followUps <= 0) listenWake() else listenCommand()
    }

    /** Текстовая команда — если распознавание не расслышало. */
    fun sendText(context: Context, text: String) {
        if (text.isBlank()) return
        val ctx = context.applicationContext
        appContext = ctx
        uiVisible = true
        if (!active) beginSession(ctx)
        cancelScheduled()
        stopCapture()
        dispatch(ctx, text.trim())
    }

    /** Сервис запущен: переходим в режим «без рук». */
    fun onServiceStarted(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        handsFree = true
        if (!active) beginSession(ctx)
        _state.value = _state.value.copy(handsFree = true)
        if (uiVisible) listenCommand() else listenWake()
    }

    /** Сервис остановлен пользователем или системой. */
    fun onServiceStopped() {
        handsFree = false
        _state.value = _state.value.copy(handsFree = false)
        if (!uiVisible) {
            shutdown()
        } else if (active) {
            stopCapture()
            listenCommand()
        }
    }

    /** Обновить фразу-обращение (из настроек). */
    fun updateWakePhrase(phrase: String) {
        _state.value = _state.value.copy(wakePhrase = phrase.ifBlank { WakeWord.DEFAULT })
    }

    // ------------------------------------------------------------------ сессия

    private fun beginSession(ctx: Context) {
        runCatching { AppGraph.init(ctx) }
        AppGraph.speaker.setPitch(AppGraph.settings.value.voicePitch)
        active = true
        awaitingWake = false
        capturing = false
        misses = 0
        failures = 0
        followUps = AppGraph.settings.value.voiceFollowUps
        val phrase = AppGraph.settings.value.wakePhrase.ifBlank { WakeWord.DEFAULT }
        _state.value = VoiceUiState(
            phase = VoicePhase.IDLE,
            handsFree = handsFree,
            wakePhrase = phrase,
            hints = defaultHints,
        )
        VoiceSession.reset()
        val greeting = runCatching { VoiceSession.greeting(ctx) }.getOrNull()
        if (greeting != null && greeting.spoken.isNotBlank()) {
            push(VoiceLine(false, greeting.spoken))
            _state.value = _state.value.copy(hints = greeting.hints.ifEmpty { defaultHints })
        }
    }

    // ------------------------------------------------------------------ слушание

    private fun listenWake() {
        awaitingWake = true
        misses = 0
        listen(awaitWake = true)
    }

    private fun listenCommand() {
        awaitingWake = false
        listen(awaitWake = false)
    }

    private fun listen(awaitWake: Boolean) {
        val ctx = appContext ?: return
        if (!active) return
        if (!hasMicrophone(ctx)) {
            fail("Разрешите доступ к микрофону — без него я не услышу команду.")
            return
        }
        if (!isAvailable(ctx)) {
            fail("На устройстве нет службы распознавания речи.")
            return
        }
        cancelScheduled()
        stopCapture()
        awaitingWake = awaitWake
        val engine = createRecognizer(ctx) ?: run {
            fail("Не удалось включить микрофон. Возможно, он занят другим приложением.")
            return
        }
        val token = ++generation
        capturing = true
        _state.value = _state.value.copy(
            phase = if (awaitWake) VoicePhase.WAKE else VoicePhase.LISTENING,
            partial = "",
            amplitude = 0f,
            error = null,
            listening = true,
        )
        engine.setRecognitionListener(listener(engine, token, awaitWake))
        val intent = recognizerIntent(ctx, awaitWake)
        schedule(100) {
            if (!active || token != generation) return@schedule
            runCatching { engine.startListening(intent) }
                .onFailure {
                    Log.w(TAG, "startListening failed", it)
                    capturing = false
                    fail("Микрофон недоступен. Попробуйте ещё раз.")
                }
        }
    }

    private fun createRecognizer(ctx: Context): SpeechRecognizer? {
        destroyRecognizer()
        return runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }
            .onFailure { CrashReporter.log(ctx, "Не удалось создать распознавание речи", it) }
            .getOrNull()
            ?.also { recognizer = it }
    }

    private fun destroyRecognizer() {
        val engine = recognizer ?: return
        recognizer = null
        runCatching { engine.setRecognitionListener(null) }
        runCatching { engine.cancel() }
        runCatching { engine.destroy() }
    }

    private fun stopCapture() {
        capturing = false
        generation += 1
        destroyRecognizer()
    }

    private fun listener(engine: SpeechRecognizer, token: Int, awaitWake: Boolean): RecognitionListener =
        object : RecognitionListener {
            private fun mine(): Boolean = active && token == generation && recognizer === engine

            override fun onReadyForSpeech(params: Bundle?) {
                if (!mine()) return
                _state.value = _state.value.copy(
                    phase = if (awaitWake) VoicePhase.WAKE else VoicePhase.LISTENING,
                    error = null,
                )
            }

            override fun onBeginningOfSpeech() {
                if (!mine()) return
                _state.value = _state.value.copy(amplitude = 0.15f)
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!mine()) return
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                val smoothed = _state.value.amplitude * 0.55f + level * 0.45f
                _state.value = _state.value.copy(amplitude = smoothed)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!mine()) return
                capturing = false
                _state.value = _state.value.copy(phase = VoicePhase.THINKING, amplitude = 0f)
            }

            override fun onError(error: Int) {
                if (!mine()) return
                capturing = false
                handleError(error, awaitWake)
            }

            override fun onResults(results: Bundle?) {
                if (!mine()) return
                capturing = false
                val text = firstResult(results)
                if (text == null) {
                    softMiss(awaitWake)
                } else {
                    handleResult(text, awaitWake)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!mine()) return
                val text = firstResult(partialResults) ?: return
                _state.value = _state.value.copy(partial = text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun recognizerIntent(ctx: Context, awaitWake: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Ждём конец фразы чуть дольше в режиме ожидания обращения:
            // иначе «Слушай, Тайм» обрывается на паузе между словами.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (awaitWake) 2200L else 1300L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (awaitWake) 2200L else 1300L,
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L)
        }

    // ------------------------------------------------------------------ результаты

    private fun handleResult(text: String, awaitWake: Boolean) {
        val ctx = appContext ?: return
        if (!active) return
        failures = 0
        val phrase = AppGraph.settings.value.wakePhrase.ifBlank { WakeWord.DEFAULT }
        if (awaitWake) {
            val match = WakeWord.split(text, phrase)
            if (!match.found) {
                failures += 1
                val delay = if (failures < 3) 220L else 900L
                schedule(delay) { if (active && awaitingWake) listenWake() }
                return
            }
            failures = 0
            misses = 0
            val command = match.command
            if (WakeWord.isStop(command)) {
                endSession(ctx)
                return
            }
            if (command.isBlank()) {
                val ack = VoiceBrain.wakeAck()
                push(VoiceLine(false, ack))
                tapFeedback(ctx)
                speakThenListen(ctx, ack) { listenCommand() }
                return
            }
            tapFeedback(ctx)
            dispatch(ctx, command)
            return
        }
        misses = 0
        dispatch(ctx, text)
    }

    private fun dispatch(ctx: Context, command: String) {
        misses = 0
        cancelScheduled()
        stopCapture()
        runCatching { AppGraph.speaker.stop() }
        push(VoiceLine(true, command))
        _state.value = _state.value.copy(
            phase = VoicePhase.THINKING,
            partial = "",
            amplitude = 0f,
            listening = false,
            error = null,
        )
        // Разбор и запись задачи — на фоновом потоке: список задач может быть большим,
        // а UI не должен «замирать» между фразами.
        val worker = Thread {
            val reply = VoiceSession.execute(ctx, command)
            main.post {
                if (!active) return@post
                deliverReply(ctx, reply)
            }
        }
        worker.name = "tm-voice-command"
        worker.isDaemon = true
        worker.start()
    }

    private fun deliverReply(ctx: Context, reply: VoiceReply) {
        if (reply.spoken.isNotBlank()) push(VoiceLine(false, reply.spoken))
        _state.value = _state.value.copy(hints = reply.hints.ifEmpty { defaultHints })

        if (reply.action is VoiceAction.Exit || reply.endSession) {
            endSession(ctx, reply.spoken)
            return
        }
        if (reply.spoken.isBlank()) {
            if (handsFree && followUps <= 0) {
                followUps = AppGraph.settings.value.voiceFollowUps
                listenWake()
            } else {
                listenCommand()
            }
            return
        }
        speakThenListen(ctx, reply.spoken) {
            if (handsFree) {
                if (followUps <= 0) {
                    followUps = AppGraph.settings.value.voiceFollowUps
                    listenWake()
                } else {
                    followUps -= 1
                    listenCommand()
                }
            } else {
                listenCommand()
            }
        }
    }

    private fun speakThenListen(ctx: Context, text: String, then: () -> Unit) {
        _state.value = _state.value.copy(phase = VoicePhase.SPEAKING, listening = false, amplitude = 0f)
        val settings = AppGraph.settings.value
        val pitch = settings.voicePitch
        AppGraph.speaker.setPitch(pitch)
        AppGraph.speaker.speakLive(text, settings.speechRate, pitch) {
            if (!active) return@speakLive
            then()
        }
    }

    private fun endSession(ctx: Context, farewell: String = "") {
        cancelScheduled()
        stopCapture()
        val stopService = handsFree
        if (farewell.isNotBlank()) {
            push(VoiceLine(false, farewell))
            _state.value = _state.value.copy(phase = VoicePhase.SPEAKING, listening = false)
            val settings = AppGraph.settings.value
            AppGraph.speaker.speakLive(farewell, settings.speechRate, settings.voicePitch) {
                if (stopService) VoiceService.stop(ctx)
                if (!uiVisible) shutdown()
                else _state.value = _state.value.copy(
                    phase = VoicePhase.IDLE,
                    listening = false,
                    partial = "",
                )
            }
        } else {
            runCatching { AppGraph.speaker.stop() }
            if (stopService) VoiceService.stop(ctx)
            if (!uiVisible) {
                shutdown()
            } else {
                _state.value = _state.value.copy(phase = VoicePhase.IDLE, listening = false, partial = "")
            }
        }
    }

    private fun softMiss(awaitWake: Boolean) {
        if (!active) return
        if (awaitWake) {
            schedule(250) { if (active && awaitingWake) listenWake() }
            return
        }
        val ctx = appContext ?: return
        misses += 1
        when {
            misses == 1 -> speakThenListen(ctx, "Не расслышал. Повторите, пожалуйста.") { listenCommand() }
            misses == 2 -> {
                if (handsFree) {
                    // В фоне не оставляем микрофон «мёртвым»: возвращаемся к фразе-обращению.
                    misses = 0
                    listenWake()
                } else {
                    _state.value = _state.value.copy(
                        phase = VoicePhase.IDLE,
                        hints = defaultHints,
                        listening = false,
                    )
                }
            }
            else -> {
                misses = 0
                if (handsFree) listenWake() else {
                    _state.value = _state.value.copy(phase = VoicePhase.IDLE, listening = false)
                }
            }
        }
    }

    private fun handleError(error: Int, awaitWake: Boolean) {
        if (!active) return
        val ctx = appContext ?: return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> {
                if (awaitWake) {
                    failures += 1
                    val delay = when {
                        failures < 3 -> 220L
                        failures < 10 -> 900L
                        else -> 2000L
                    }
                    schedule(delay) { if (active && awaitingWake) listenWake() }
                } else {
                    softMiss(false)
                }
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                fail("Разрешите доступ к микрофону в настройках Android.")

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            -> {
                failures += 1
                val message = "Распознавание речи требует интернета. Проверьте подключение."
                _state.value = _state.value.copy(
                    phase = VoicePhase.ERROR,
                    error = message,
                    listening = false,
                    amplitude = 0f,
                )
                if (handsFree) {
                    val delay = (1000L * (1 shl failures.coerceAtMost(5))).coerceAtMost(30_000L)
                    schedule(delay) { if (active) listenWake() }
                }
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> schedule(700) { if (active) listen(awaitWake) }

            // Google ограничивает частоту запросов: в фоне ждём дольше и пробуем снова.
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                failures += 1
                val delay = (15_000L * failures.coerceAtMost(4)).coerceAtMost(60_000L)
                _state.value = _state.value.copy(
                    phase = VoicePhase.ERROR,
                    error = "Слишком много запросов к распознаванию. Подожду немного.",
                    listening = false,
                )
                if (handsFree) schedule(delay) { if (active) listenWake() }
            }

            SpeechRecognizer.ERROR_CLIENT -> schedule(350) { if (active) listen(awaitWake) }

            else -> {
                _state.value = _state.value.copy(
                    phase = VoicePhase.ERROR,
                    error = "Не удалось послушать. Попробуйте ещё раз.",
                    listening = false,
                    amplitude = 0f,
                )
                if (handsFree) schedule(1500) { if (active) listenWake() }
            }
        }
    }

    private fun fail(message: String) {
        stopCapture()
        _state.value = _state.value.copy(
            phase = VoicePhase.ERROR,
            error = message,
            listening = false,
            amplitude = 0f,
        )
        Log.w(TAG, message)
    }

    // ------------------------------------------------------------------ мелочи

    /** Короткая вибрация — «я тебя услышал». */
    private fun tapFeedback(ctx: Context) {
        if (!AppGraph.settings.value.vibrate) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(14L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun push(line: VoiceLine) {
        val history = (_state.value.history + line).takeLast(40)
        _state.value = _state.value.copy(history = history)
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        cancelScheduled()
        val runnable = Runnable {
            pendingTask = null
            if (active) block()
        }
        pendingTask = runnable
        main.postDelayed(runnable, delayMs)
    }

    private fun cancelScheduled() {
        pendingTask?.let { main.removeCallbacks(it) }
        pendingTask = null
    }
}
