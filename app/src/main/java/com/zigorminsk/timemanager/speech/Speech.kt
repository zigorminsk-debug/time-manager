package com.zigorminsk.timemanager.speech

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Голосовой ввод через системную службу распознавания речи (Google). */
object Dictation {

    fun isAvailable(context: Context): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    fun intent(context: Context, prompt: String = "Говорите…", language: String = "ru-RU"): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    /** Достаёт распознанный текст из результата активности. */
    fun extract(resultCode: Int, data: Intent?): String? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val variants = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        return variants?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}

/**
 * Озвучка задач и напоминаний.
 *
 * Умеет и один короткий ответ ([speak]), и «живую» речь по предложениям
 * ([speakLive]) — с паузами между фразами, выбранным русским голосом и
 * колбэком «закончил говорить» (нужен голосовому движку, чтобы снова
 * включить микрофон ровно в тот момент, когда замолчал динамик).
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ready = false
    private var russianAvailable = true

    private var pendingText: String? = null
    private var pendingRate = 1f
    private var pendingPitch = 1f
    private var pendingLive = false
    private var pendingDone: (() -> Unit)? = null

    private var pitch = 1f
    private var liveGeneration = 0
    private var liveRemaining = 0
    private var liveDone: (() -> Unit)? = null

    val speaking = MutableStateFlow(false)
    val russianVoiceMissing = MutableStateFlow(false)

    /** Название выбранного русского голоса — показывается в настройках. */
    val voiceName = MutableStateFlow<String?>(null)

    init {
        runCatching { tts = TextToSpeech(appContext, this) }
            .onFailure {
                Log.w("TM/TTS", "TextToSpeech init failed", it)
                russianVoiceMissing.value = true
            }
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            russianVoiceMissing.value = true
            return
        }
        val result = runCatching { engine.setLanguage(Locale("ru", "RU")) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        russianAvailable = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
        russianVoiceMissing.value = !russianAvailable

        runCatching {
            val voice = chooseVoice(engine)
            if (voice != null) {
                engine.voice = voice
                voiceName.value = voice.name
            }
        }

        engine.setSpeechRate(pendingRate.coerceIn(0.5f, 2f))
        engine.setPitch(pitch.coerceIn(0.6f, 1.6f))
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                finishUtterance(utteranceId)
            }

            @Suppress("OVERRIDE_DEPRECATION", "DeprecatedCall")
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishUtterance(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w("TM/TTS", "utterance error $errorCode")
                finishUtterance(utteranceId)
            }
        })

        ready = true
        val text = pendingText ?: return
        val live = pendingLive
        val done = pendingDone
        val rate = pendingRate
        val tone = pendingPitch
        pendingText = null
        pendingDone = null
        if (live) {
            speakLive(text, rate, tone, done)
        } else {
            speak(text, rate)
        }
    }

    /** Короткий ответ (напоминание, тест голоса). */
    fun speak(text: String, rate: Float = 1f) {
        if (text.isBlank()) return
        val clean = text.trim()
        val engine = tts
        pendingDone = null
        if (!ready || engine == null) {
            pendingText = clean
            pendingRate = rate
            pendingPitch = pitch
            pendingLive = false
            return
        }
        if (!russianAvailable) {
            russianVoiceMissing.value = true
            return
        }
        runCatching {
            engine.stop()
            engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
            engine.setPitch(pitch.coerceIn(0.6f, 1.6f))
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, speechParams(), "tm-${System.currentTimeMillis()}")
        }.onFailure { Log.w("TM/TTS", "speak failed", it) }
    }

    /**
     * «Живая» речь: текст режется на предложения и произносится с естественными
     * паузами. [onFinished] вызывается один раз — когда договорил последнее
     * предложение (или если озвучка сорвалась).
     */
    fun speakLive(
        text: String,
        rate: Float = 1f,
        pitch: Float = 1f,
        onFinished: (() -> Unit)? = null,
    ) {
        val clean = text.trim()
        if (clean.isBlank()) {
            onFinished?.invoke()
            return
        }
        this.pitch = pitch.coerceIn(0.6f, 1.6f)
        val engine = tts
        if (!ready || engine == null) {
            pendingText = clean
            pendingRate = rate
            pendingPitch = this.pitch
            pendingLive = true
            pendingDone = onFinished
            return
        }
        if (!russianAvailable) {
            russianVoiceMissing.value = true
            onFinished?.invoke()
            return
        }

        val chunks = splitSentences(clean)
        liveGeneration += 1
        val generation = liveGeneration
        liveDone = onFinished
        liveRemaining = chunks.size
        speaking.value = true

        runCatching {
            engine.stop()
            engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
            engine.setPitch(this.pitch)
            val params = speechParams()
            chunks.forEachIndexed { index, chunk ->
                val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(chunk, mode, params, "live-$generation-$index")
            }
        }.onFailure {
            Log.w("TM/TTS", "speakLive failed", it)
            speaking.value = false
            liveRemaining = 0
            val done = liveDone
            liveDone = null
            done?.invoke()
        }
    }

    fun setPitch(value: Float) {
        pitch = value.coerceIn(0.6f, 1.6f)
        runCatching {
            tts?.setPitch(pitch)
            tts?.setSpeechRate(pendingRate.coerceIn(0.5f, 2f))
        }
    }

    fun stop() {
        liveGeneration += 1
        liveRemaining = 0
        liveDone = null
        pendingText = null
        pendingDone = null
        runCatching { tts?.stop() }
        speaking.value = false
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    // ------------------------------------------------------------------ внутреннее

    private fun speechParams(): Bundle = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
    }

    private fun finishUtterance(utteranceId: String?) {
        val id = utteranceId ?: return
        if (!id.startsWith("live-")) {
            speaking.value = false
            return
        }
        val generation = id.split('-').getOrNull(1)?.toIntOrNull() ?: return
        if (generation != liveGeneration) return
        liveRemaining -= 1
        if (liveRemaining > 0) return
        liveRemaining = 0
        speaking.value = false
        val done = liveDone
        liveDone = null
        if (done != null) main.postDelayed({ done() }, 140)
    }

    private fun chooseVoice(engine: TextToSpeech): Voice? = runCatching {
        val voices = engine.voices ?: return@runCatching null
        voices.asSequence()
            .filter { it.locale.language.equals("ru", ignoreCase = true) }
            .sortedWith(
                compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )
            .firstOrNull()
    }.getOrNull()

    /**
     * Делит текст на фразы: точка/вопрос/восклицание/многоточие — граница.
     * Слишком длинные куски режет по запятым, чтобы паузы были живее.
     */
    private fun splitSentences(text: String): List<String> {
        val pieces = text.split(Regex("(?<=[.!?…])\\s+"))
        val result = mutableListOf<String>()
        for (raw in pieces) {
            var piece = raw.trim()
            if (piece.isEmpty()) continue
            while (piece.length > 220) {
                var cut = piece.lastIndexOf(',', 200)
                if (cut < 60) cut = piece.lastIndexOf(' ', 200)
                if (cut <= 0) cut = 200
                result += piece.substring(0, cut).trim().trimEnd(',')
                piece = piece.substring(cut).trim().trimStart(',')
            }
            if (piece.isNotEmpty()) result += piece
        }
        return result.ifEmpty { listOf(text.trim()) }
    }
}
