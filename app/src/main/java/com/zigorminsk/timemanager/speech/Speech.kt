package com.zigorminsk.timemanager.speech

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

/** Озвучка задач и напоминаний. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var russianAvailable = true
    private var pendingText: String? = null
    private var pendingRate = 1f

    val speaking = MutableStateFlow(false)
    val russianVoiceMissing = MutableStateFlow(false)

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
        val result = runCatching { engine.setLanguage(Locale("ru", "RU")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        russianAvailable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        russianVoiceMissing.value = !russianAvailable
        engine.setSpeechRate(pendingRate)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking.value = true }
            override fun onDone(utteranceId: String?) { speaking.value = false }
            override fun onError(utteranceId: String?) { speaking.value = false }
        })
        ready = true
        pendingText?.let { text ->
            pendingText = null
            speak(text, pendingRate)
        }
    }

    fun speak(text: String, rate: Float = 1f) {
        if (text.isBlank()) return
        pendingRate = rate
        val engine = tts
        if (!ready || engine == null) {
            pendingText = text
            return
        }
        if (!russianAvailable) {
            russianVoiceMissing.value = true
            return
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tm-${System.currentTimeMillis()}")
    }

    fun stop() {
        runCatching { tts?.stop() }
        speaking.value = false
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
