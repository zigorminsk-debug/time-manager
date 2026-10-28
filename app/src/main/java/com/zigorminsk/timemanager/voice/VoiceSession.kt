package com.zigorminsk.timemanager.voice

import android.content.Context
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.notify.AlarmScheduler
import com.zigorminsk.timemanager.notify.Notifications
import com.zigorminsk.timemanager.ui.MainActivity
import com.zigorminsk.timemanager.util.CrashReporter

/**
 * Исполнение голосовых команд: связывает «мозг» ([VoiceBrain]) с хранилищем,
 * будильниками и интерфейсом. Здесь же живёт короткая память диалога:
 * о каких задачах только что говорили и какой задаче не хватает времени.
 */
object VoiceSession {

    private const val PENDING_TTL_MS = 10 * 60_000L

    private var lastTaskIds: List<String> = emptyList()
    private var lastSpoken: String? = null
    private var pendingTaskId: String? = null
    private var pendingTitle: String = ""
    private var pendingAt: Long = 0L

    /** Обработать фразу и сразу применить действие. */
    @Synchronized
    fun execute(context: Context, text: String): VoiceReply {
        val appContext = context.applicationContext
        val settings = AppGraph.settings.value
        val now = System.currentTimeMillis()
        expirePending(now)

        val brainContext = VoiceContext(
            now = now,
            tasks = AppGraph.repo.data.activeTasks(),
            places = AppGraph.repo.data.places,
            lastTaskIds = lastTaskIds,
            lastSpoken = lastSpoken,
            pendingTaskId = pendingTaskId,
            pendingTitle = pendingTitle,
            speechRate = settings.speechRate,
            chatty = settings.voiceChatty,
        )

        val phrase = settings.wakePhrase.ifBlank { WakeWord.DEFAULT }
        val wake = WakeWord.split(text, phrase)
        val command = if (wake.found && wake.command.isNotBlank()) wake.command else text
        val reply = runCatching { VoiceBrain.handle(command, brainContext) }
            .getOrElse { error ->
                CrashReporter.log(appContext, "Голосовая команда: $command", error)
                VoiceReply("Не получилось разобрать команду. Попробуйте ещё раз.")
            }

        runCatching { apply(appContext, reply) }
            .onFailure { CrashReporter.log(appContext, "Голосовое действие", it) }

        lastSpoken = reply.spoken.takeIf { it.isNotBlank() }
        if (reply.taskIds.isNotEmpty()) lastTaskIds = reply.taskIds
        return reply
    }

    /** Приветствие с краткой сводкой на сегодня. */
    fun greeting(context: Context): VoiceReply {
        val settings = AppGraph.settings.value
        val now = System.currentTimeMillis()
        val reply = VoiceBrain.greeting(
            VoiceContext(
                now = now,
                tasks = AppGraph.repo.data.activeTasks(),
                places = AppGraph.repo.data.places,
                lastTaskIds = lastTaskIds,
                lastSpoken = lastSpoken,
                speechRate = settings.speechRate,
                chatty = settings.voiceChatty,
            ),
        )
        if (reply.taskIds.isNotEmpty()) lastTaskIds = reply.taskIds
        return reply
    }

    fun reset() {
        lastTaskIds = emptyList()
        lastSpoken = null
        pendingTaskId = null
        pendingTitle = ""
        pendingAt = 0L
    }

    /** Короткая сводка для уведомления сервиса. */
    fun summary(): String {
        val active = AppGraph.repo.data.activeTasks()
        return if (active.isEmpty()) "Задач нет" else "Активных задач: ${active.size}"
    }

    // ------------------------------------------------------------------ действия

    private fun apply(context: Context, reply: VoiceReply) {
        when (val action = reply.action) {
            null -> Unit

            is VoiceAction.AddTask -> {
                val task = Task(
                    title = action.title.ifBlank { "Без названия" },
                    dueAt = action.dueAt,
                    hasTime = action.hasTime,
                    placeId = action.placeId,
                    placeName = action.placeName,
                )
                AppGraph.repo.addTask(task)
                lastTaskIds = listOf(task.id)
                if (action.dueAt == null && action.placeId == null) {
                    pendingTaskId = task.id
                    pendingTitle = task.title
                    pendingAt = System.currentTimeMillis()
                } else {
                    clearPending()
                }
            }

            is VoiceAction.SetDue -> {
                AppGraph.repo.moveTask(action.taskId, action.dueAt, action.hasTime)
                AppGraph.repo.taskById(action.taskId)?.let { AlarmScheduler.scheduleTask(context, it) }
                lastTaskIds = listOf(action.taskId)
                clearPending()
            }

            is VoiceAction.CompleteTask -> {
                Notifications.cancelTask(context, action.taskId)
                AppGraph.repo.completeTask(action.taskId)
                lastTaskIds = listOf(action.taskId)
                clearPending()
            }

            is VoiceAction.SnoozeTask -> {
                AppGraph.repo.postponeTask(action.taskId, action.minutes)
                AppGraph.repo.taskById(action.taskId)?.let { AlarmScheduler.scheduleTask(context, it) }
                lastTaskIds = listOf(action.taskId)
                clearPending()
            }

            is VoiceAction.MoveTask -> {
                AppGraph.repo.moveTask(action.taskId, action.dueAt, action.hasTime)
                AppGraph.repo.taskById(action.taskId)?.let { AlarmScheduler.scheduleTask(context, it) }
                lastTaskIds = listOf(action.taskId)
                clearPending()
            }

            is VoiceAction.ArchiveTask -> {
                Notifications.cancelTask(context, action.taskId)
                AppGraph.repo.archiveTask(action.taskId)
                lastTaskIds = listOf(action.taskId)
                clearPending()
            }

            is VoiceAction.SetSpeechRate -> {
                AppGraph.settings.update { it.copy(speechRate = action.rate) }
            }

            is VoiceAction.OpenTab -> {
                MainActivity.openTab.value = action.tab
            }

            VoiceAction.Exit -> Unit
        }
    }

    private fun expirePending(now: Long) {
        if (pendingTaskId != null && now - pendingAt > PENDING_TTL_MS) clearPending()
    }

    private fun clearPending() {
        pendingTaskId = null
        pendingTitle = ""
        pendingAt = 0L
    }
}
