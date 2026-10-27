package com.zigorminsk.timemanager.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.zigorminsk.timemanager.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал сбоев: пишет фатальные ошибки и «пойманные» исключения в файлы приложения,
 * чтобы их можно было показать пользователю и отправить разработчику.
 */
object CrashReporter {

    private const val MAX_LOG_BYTES = 128 * 1024

    fun crashFile(context: Context): File = File(context.filesDir, "last_crash.txt")

    fun logFile(context: Context): File = File(context.filesDir, "app_log.txt")

    /** Перехватчик фатальных ошибок (ставится в Application.onCreate). */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun log(context: Context, message: String, throwable: Throwable? = null) {
        runCatching {
            val f = logFile(context)
            if (f.exists() && f.length() > MAX_LOG_BYTES) f.delete()
            f.appendText(
                buildString {
                    append(timestamp()).append(' ').append(message)
                    if (throwable != null) append('\n').append(Log.getStackTraceString(throwable))
                    append('\n')
                },
            )
        }
    }

    fun hasCrash(context: Context): Boolean = runCatching { crashFile(context).exists() }.getOrDefault(false)

    fun readCrash(context: Context): String? = runCatching {
        crashFile(context).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clearCrash(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    fun clearLog(context: Context) {
        runCatching { logFile(context).delete() }
    }

    /** Полный отчёт: устройство + последний сбой + хвост журнала. */
    fun report(context: Context): String = buildString {
        append("Тайм-менеджер ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("Время: ").append(timestamp()).append("\n\n")

        val crash = readCrash(context)
        if (!crash.isNullOrBlank()) {
            append("=== Последний сбой ===\n").append(crash).append("\n\n")
        } else {
            append("=== Сбоев не зафиксировано ===\n\n")
        }

        val log = runCatching {
            logFile(context).takeIf { it.exists() }?.readText()?.takeLast(8000)
        }.getOrNull()
        if (!log.isNullOrBlank()) {
            append("=== Журнал ===\n").append(log)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val text = buildString {
            append("Время: ").append(timestamp()).append('\n')
            append("Версия: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Поток: ").append(thread.name).append("\n\n")
            append(Log.getStackTraceString(throwable))
        }
        crashFile(context).writeText(text)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
}
