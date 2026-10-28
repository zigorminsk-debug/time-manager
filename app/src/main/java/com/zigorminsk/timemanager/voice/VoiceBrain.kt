package com.zigorminsk.timemanager.voice

import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.util.RuTime
import com.zigorminsk.timemanager.util.RuTimeParser
import java.time.Instant
import java.time.ZoneId

/**
 * Состояние, о котором ассистент знает в момент ответа.
 * Передаётся снимком — движок не держит ссылку на репозиторий,
 * поэтому вся логика ответов тестируется на обычной JVM.
 */
data class VoiceContext(
    val now: Long = System.currentTimeMillis(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val tasks: List<Task> = emptyList(),
    val places: List<Place> = emptyList(),
    /** Задачи, о которых только что шла речь (для «первую», «вторую», «эту»). */
    val lastTaskIds: List<String> = emptyList(),
    /** Последняя произнесённая фраза — для «повтори». */
    val lastSpoken: String? = null,
    /** Задача, которой не хватает времени: «Напомни купить хлеб» → «в 19:00». */
    val pendingTaskId: String? = null,
    val pendingTitle: String = "",
    val speechRate: Float = 1f,
    /** Больше живых словечек в ответах. */
    val chatty: Boolean = true,
)

/** Действие, которое движок должен применить к данным. */
sealed interface VoiceAction {
    data class AddTask(
        val title: String,
        val dueAt: Long?,
        val hasTime: Boolean,
        val placeId: String?,
        val placeName: String,
    ) : VoiceAction

    data class SetDue(val taskId: String, val dueAt: Long, val hasTime: Boolean) : VoiceAction

    data class CompleteTask(val taskId: String, val title: String) : VoiceAction

    data class SnoozeTask(val taskId: String, val title: String, val minutes: Int) : VoiceAction

    data class MoveTask(val taskId: String, val title: String, val dueAt: Long, val hasTime: Boolean) : VoiceAction

    data class ArchiveTask(val taskId: String, val title: String) : VoiceAction

    data class SetSpeechRate(val rate: Float) : VoiceAction

    /** Открыть раздел приложения: today / all / archive / places / settings. */
    data class OpenTab(val tab: String) : VoiceAction

    /** Завершить сессию: «пока», «выключись», «стоп». */
    data object Exit : VoiceAction
}

/** Ответ ассистента: что сказать, что сделать и что показать подсказками. */
data class VoiceReply(
    val spoken: String,
    val action: VoiceAction? = null,
    val keepListening: Boolean = true,
    val endSession: Boolean = false,
    val hints: List<String> = emptyList(),
    val taskIds: List<String> = emptyList(),
) {
    val silence: Boolean get() = spoken.isBlank()
}

/**
 * «Мозг» голосового помощника: русские команды → ответ + действие.
 *
 * Понимает не только строгие команды, но и живую речь:
 * «Слушай, что у меня на сегодня?», «напомни позвонить маме завтра в три»,
 * «отложи на двадцать минут», «выполни первую», «перенеси на завтра в десять».
 */
object VoiceBrain {

    private val ADD_VERBS = listOf(
        "напомни", "напомнить", "напоминание", "добавь", "добавить",
        "запиши", "записать", "создай", "создать", "поставь", "поставить",
        "запланируй", "запланировать", "новая задача", "задача",
    )

    private val COMPLETE_VERBS = listOf(
        "выполнил", "выполнила", "выполнено", "выполни", "выполнить",
        "сделал", "сделала", "сделано", "сделай", "готово",
        "отметь", "отметить", "закрой", "закрыть", "закончил", "закончила",
        "заверши", "завершил", "завершила", "убери из списка",
    )

    private val ARCHIVE_VERBS = listOf("удали", "удалить", "сотри", "стереть", "убери из списка", "убери", "в архив")

    private val SNOOZE_VERBS = listOf("отложи", "отложить", "отсрочь", "напомни позже")

    private val MOVE_VERBS = listOf("перенеси", "перенести", "передвинь", "сдвинь", "переставь")

    private val GENERIC_TASK_WORDS = setOf(
        "эту", "эта", "это", "этот", "ее", "его", "задачу", "задача", "задание",
        "последнюю", "последняя", "последнее", "текущую", "текущая", "следующую",
    )

    private val QUESTION_STARTS = listOf(
        "что", "как", "какой", "какая", "какие", "когда", "где", "куда",
        "почему", "зачем", "сколько", "кто", "чем", "можно",
    )

    private val DEFAULT_HINTS = listOf("Что на сегодня?", "Напомни позвонить маме завтра в 9", "Что ты умеешь?")
    private val ADD_HINTS = listOf("Что на сегодня?", "Напомни купить хлеб", "Что ты умеешь?")

    /** Короткая реакция на фразу-обращение. */
    fun wakeAck(now: Long = System.currentTimeMillis()): String {
        val options = arrayOf("Слушаю.", "Да?", "Я здесь.", "Слушаю вас.")
        return options[Math.floorMod((now / 1000L).toInt(), options.size)]
    }

    /** Приветствие при открытии экрана ассистента. */
    fun greeting(ctx: VoiceContext): VoiceReply {
        val today = todayTasks(ctx)
        val next = ctx.tasks.filter { it.isActive && it.dueAt != null && it.dueAt!! >= ctx.now }
            .minByOrNull { it.dueAt!! }
        val tail = when {
            today.isEmpty() && ctx.tasks.none { it.isActive } ->
                "Задач пока нет. Скажите «напомни…», и я запишу."
            today.isEmpty() -> "На сегодня задач нет. Можно отдохнуть."
            next != null -> "На сегодня ${today.size} ${plural(today.size, "задача", "задачи", "задач")}. " +
                "Ближайшая — «${next.title}», ${untilText(next.dueAt!!, ctx)}."
            else -> "На сегодня ${today.size} ${plural(today.size, "задача", "задачи", "задач")}."
        }
        return VoiceReply(
            spoken = tail,
            hints = DEFAULT_HINTS,
            taskIds = today.map { it.id },
        )
    }

    // ------------------------------------------------------------------ разбор

    fun handle(raw: String, ctx: VoiceContext): VoiceReply {
        val text = raw.trim()
        val n = WakeWord.normalize(text)
        val seed = Math.floorMod(n.hashCode() * 31 + (ctx.now / 60_000L).toInt(), 9973)

        if (n.isBlank()) return retry(seed, "Не расслышал. Повторите, пожалуйста.")

        // 1. Остановка и выход
        if (WakeWord.isStop(n)) {
            val bye = n.contains("пока") || n.contains("свидания") || n.contains("выключись")
            return VoiceReply(
                spoken = if (bye) pick(seed, "Хорошо, до встречи.", "До связи.", "Выключаюсь.") else "",
                action = VoiceAction.Exit,
                keepListening = false,
                endSession = true,
                hints = DEFAULT_HINTS,
            )
        }

        // 2. «Повтори»
        if (hasWord(n, "повтори") || hasWord(n, "еще") && hasWord(n, "раз")) {
            val last = ctx.lastSpoken?.takeIf { it.isNotBlank() }
            return VoiceReply(
                spoken = last ?: "Пока нечего повторять. Спросите, что на сегодня.",
                hints = DEFAULT_HINTS,
            )
        }

        // 3. «Что ты умеешь»
        if (hasPhrase(n, "что ты умеешь") || hasWord(n, "помощь") || hasPhrase(n, "как пользоваться") ||
            hasPhrase(n, "какие команды") || hasPhrase(n, "твои команды")
        ) {
            return VoiceReply(
                spoken = "Могу записать задачу, проговорить день, выполнить или отложить дело, " +
                    "перенести его на другое время и открыть раздел. " +
                    "Например: «что на сегодня», «напомни позвонить маме завтра в три», " +
                    "«отложи на двадцать минут», «выполни первую».",
                hints = DEFAULT_HINTS,
            )
        }

        // 4. Приветствие и благодарность
        if (hasAnyWord(n, "привет", "здравствуй", "здравствуйте", "хай") ||
            hasPhrase(n, "доброе утро") || hasPhrase(n, "добрый день") || hasPhrase(n, "добрый вечер")
        ) {
            val hello = pick(seed, "Привет!", "Здравствуйте!", "Привет, я на связи.")
            val brief = greeting(ctx)
            return brief.copy(
                spoken = "$hello ${brief.spoken}",
                hints = brief.hints.ifEmpty { DEFAULT_HINTS },
            )
        }
        if (hasAnyWord(n, "спасибо", "благодарю")) {
            return VoiceReply(
                spoken = pick(seed, "Пожалуйста.", "Всегда рад помочь.", "Обращайтесь."),
                hints = DEFAULT_HINTS,
            )
        }

        // 5. Темп речи
        if (hasAnyWord(n, "быстрее", "медленнее", "помедленнее") && n.split(" ").size <= 5) {
            val faster = hasWord(n, "быстрее")
            val rate = (ctx.speechRate + if (faster) 0.2f else -0.2f).coerceIn(0.6f, 1.6f)
            return VoiceReply(
                spoken = if (faster) {
                    pick(seed, "Хорошо, говорю быстрее.", "Ускоряюсь.")
                } else {
                    pick(seed, "Хорошо, говорю медленнее.", "Замедляюсь.")
                },
                action = VoiceAction.SetSpeechRate(rate),
            )
        }

        // 6. Время и дата
        if (hasPhrase(n, "который час") || hasPhrase(n, "сколько времени") ||
            hasPhrase(n, "какое сегодня число") || hasPhrase(n, "какое число") ||
            hasPhrase(n, "какой сегодня день") || hasPhrase(n, "какой день недели")
        ) {
            val zdt = Instant.ofEpochMilli(ctx.now).atZone(ctx.zone)
            val weekday = RuTime.WEEKDAYS_NOM[zdt.dayOfWeek.value - 1]
            val date = "${zdt.dayOfMonth} ${RuTime.MONTHS_GEN[zdt.monthValue - 1]}"
            return VoiceReply(
                spoken = "Сейчас ${RuTime.hhmm(zdt.hour, zdt.minute)}. Сегодня $weekday, $date.",
                hints = DEFAULT_HINTS,
            )
        }

        // 7. Уточнение времени для только что добавленной задачи
        val pendingId = ctx.pendingTaskId
        if (pendingId != null && ctx.pendingTitle.isNotBlank() && startsWithAny(n, ADD_VERBS) == null) {
            val timeOnly = timeOnly(text, ctx)
            if (timeOnly != null) {
                val (millis, hasTime) = timeOnly
                val whenText = RuTime.formatDue(millis, hasTime, ctx.now, ctx.zone).lowercase()
                return VoiceReply(
                    spoken = "Хорошо, «${ctx.pendingTitle}» — $whenText. Напомню.",
                    action = VoiceAction.SetDue(pendingId, millis, hasTime),
                    hints = listOf("Что на сегодня?", "Что дальше?", "Выполни первую"),
                    taskIds = listOf(pendingId),
                )
            }
        }

        // 8. Открыть раздел
        val tabWord = when {
            hasAnyWord(n, "настройки", "настройках") -> "settings"
            hasAnyWord(n, "места", "местам", "местах") && hasAnyWord(n, "открой", "покажи", "перейди") -> "places"
            hasAnyWord(n, "архив", "архиве") && hasAnyWord(n, "открой", "покажи", "перейди") -> "archive"
            hasAnyWord(n, "задачи", "задачах", "список") && hasAnyWord(n, "все", "всем") &&
                hasAnyWord(n, "открой", "покажи", "перейди") -> "all"
            else -> null
        }
        if (tabWord != null && hasAnyWord(n, "открой", "покажи", "перейди", "открывай")) {
            return VoiceReply(
                spoken = when (tabWord) {
                    "settings" -> "Открываю настройки."
                    "places" -> "Открываю места."
                    "archive" -> "Открываю архив."
                    else -> "Открываю все задачи."
                },
                action = VoiceAction.OpenTab(tabWord),
            )
        }

        // 9. Сколько задач
        if (hasPhrase(n, "сколько задач") || hasPhrase(n, "сколько у меня задач") ||
            hasPhrase(n, "сколько дел") || hasPhrase(n, "сколько у меня дел")
        ) {
            val active = ctx.tasks.filter { it.isActive }
            val overdue = active.count { it.dueAt != null && it.dueAt < ctx.now }
            val tail = if (overdue > 0) " Из них $overdue ${plural(overdue, "просрочена", "просрочены", "просрочено")}." else ""
            return VoiceReply(
                spoken = "Активных ${plural(active.size, "задача", "задачи", "задач")}: ${active.size}.$tail",
                hints = listOf("Что на сегодня?", "Что просрочено", "Что дальше?"),
                taskIds = active.map { it.id },
            )
        }

        // 10. Просроченные
        if (hasPrefix(n, "просроч") || hasPhrase(n, "что просрочено") || hasPhrase(n, "что я пропустил")) {
            val overdue = ctx.tasks.filter { it.isActive && it.dueAt != null && it.dueAt < ctx.now }
                .sortedBy { it.dueAt }
            if (overdue.isEmpty()) {
                return VoiceReply(
                    spoken = "Просроченных задач нет. Всё под контролем.",
                    hints = DEFAULT_HINTS,
                )
            }
            return VoiceReply(
                spoken = "Просрочено ${overdue.size}: " +
                    overdue.take(3).joinToString(". ") { describeTask(it, ctx) } + ".",
                hints = listOf("Отложи на 20 минут", "Выполни первую", "Что на сегодня?"),
                taskIds = overdue.map { it.id },
            )
        }

        // 11. Места
        if (hasPhrase(n, "мои места") || hasPhrase(n, "какие места") || hasPhrase(n, "список мест")) {
            if (ctx.places.isEmpty()) {
                return VoiceReply(
                    spoken = "Мест пока нет. Добавьте их в разделе «Места», и я напомню при прибытии.",
                    hints = DEFAULT_HINTS,
                )
            }
            val names = ctx.places.map { it.name }.filter { it.isNotBlank() }
            return VoiceReply(
                spoken = "Мои места: ${names.joinToString(", ")}. Напомню, когда будете рядом.",
                hints = listOf("Что на сегодня?", "Открой места"),
            )
        }

        // 12. Все задачи
        if (hasPhrase(n, "все задачи") || hasPhrase(n, "список задач") || hasPhrase(n, "все дела")) {
            val active = ctx.tasks.filter { it.isActive }
                .sortedWith(compareBy<Task>({ it.dueAt ?: Long.MAX_VALUE }, { it.title }))
            if (active.isEmpty()) {
                return VoiceReply(
                    spoken = "Список пуст. Скажите «напомни…», и я запишу задачу.",
                    hints = ADD_HINTS,
                )
            }
            val shown = active.take(3)
            val tail = if (active.size > shown.size) " И ещё ${active.size - shown.size}." else ""
            return VoiceReply(
                spoken = "Активных ${plural(active.size, "задача", "задачи", "задач")}: ${active.size}. " +
                    shown.joinToString(". ") { describeTask(it, ctx) } + "." + tail,
                hints = listOf("Выполни первую", "Что на сегодня?", "Что просрочено"),
                taskIds = active.map { it.id },
            )
        }

        // 13. Планы и следующая задача
        if (hasPhrase(n, "что дальше") || hasPhrase(n, "следующая задача") ||
            hasPhrase(n, "что сейчас") || hasPhrase(n, "что у меня сейчас")
        ) {
            return nextReply(ctx, seed)
        }
        if (hasPhrase(n, "что на завтра") || hasPhrase(n, "завтрашние задачи") ||
            hasPhrase(n, "планы на завтра") || hasPhrase(n, "план на завтра")
        ) {
            val tomorrow = RuTime.dateOf(ctx.now, ctx.zone).plusDays(1)
            val tasks = ctx.tasks.filter { it.isActive && it.dueAt != null && RuTime.dateOf(it.dueAt!!, ctx.zone) == tomorrow }
            return planReply(tasks, ctx, "завтра", seed)
        }
        val asksAboutTasks = hasAnyWord(n, "задачи", "задач", "задачах", "задачам", "дела", "делах") &&
            hasAnyWord(n, "какие", "мои", "покажи", "сегодня", "есть", "список")
        if (hasPhrase(n, "что на сегодня") || hasPhrase(n, "что у меня сегодня") ||
            hasPhrase(n, "планы на сегодня") || hasPhrase(n, "план на сегодня") ||
            hasPhrase(n, "какие задачи") || hasPhrase(n, "что у меня") ||
            hasPhrase(n, "расписание") || hasPhrase(n, "мой день") || asksAboutTasks
        ) {
            val tasks = todayTasks(ctx)
            val reply = planReply(tasks, ctx, "сегодня", seed)
            return reply.copy(
                spoken = reply.spoken + overdueNote(ctx),
                taskIds = tasks.map { it.id },
            )
        }
        if (hasPhrase(n, "проговори день") || hasPhrase(n, "расскажи про день")) {
            val tasks = todayTasks(ctx)
            val reply = planReply(tasks, ctx, "сегодня", seed)
            return reply.copy(spoken = "Задачи на сегодня: ${tasks.size}. " + reply.spoken + overdueNote(ctx))
        }

        // 14. Выполнить
        val completeQuery = startsWithAny(n, COMPLETE_VERBS)
        if (completeQuery != null) {
            val query = stripCommandWords(text, COMPLETE_VERBS)
            val task = resolveTask(query, ctx)
            if (task == null) {
                return notFound(query, ctx, seed, "выполнить")
            }
            return VoiceReply(
                spoken = pick(
                    seed,
                    "Готово: «${task.title}» в архиве.",
                    "«${task.title}» — выполнено.",
                    "Отметил «${task.title}» выполненной.",
                ),
                action = VoiceAction.CompleteTask(task.id, task.title),
                hints = listOf("Что на сегодня?", "Что дальше?"),
                taskIds = listOf(task.id),
            )
        }

        // 15. В архив
        val archiveQuery = startsWithAny(n, ARCHIVE_VERBS)
        if (archiveQuery != null) {
            val query = stripCommandWords(text, ARCHIVE_VERBS)
            val task = resolveTask(query, ctx) ?: if (query.isBlank()) urgentTask(ctx) else null
            if (task == null) {
                return notFound(query, ctx, 0, "убрать в архив")
            }
            return VoiceReply(
                spoken = pick(
                    seed,
                    "«${task.title}» — в архиве. Вернуть можно в разделе «Архив».",
                    "Убрал «${task.title}» в архив.",
                ),
                action = VoiceAction.ArchiveTask(task.id, task.title),
                hints = listOf("Что на сегодня?", "Открой архив"),
                taskIds = listOf(task.id),
            )
        }

        // 16. Перенести (и «перенеси на 20 минут» = отложить)
        val moveQuery = startsWithAny(n, MOVE_VERBS)
        if (moveQuery != null) {
            val body = stripCommandWords(text, MOVE_VERBS)
            val parsed = runCatching { RuTimeParser.parse(digitsForTime(body), ctx.now, ctx.zone) }.getOrNull()
            val millis = parsed?.millis
            if (millis == null) {
                val minutes = parseMinutes(n)
                val query = stripSnoozeTail(body)
                if (minutes != null) {
                    val target = resolveTask(query, ctx) ?: urgentTask(ctx)
                    if (target != null) return snoozeReply(target, minutes, ctx, seed)
                }
                val task = resolveTask(parsed?.title ?: body, ctx)
                return VoiceReply(
                    spoken = if (task != null) {
                        "На когда перенести «${task.title}»? Например: «на завтра в десять»."
                    } else {
                        "На когда перенести? Например: «на завтра в десять»."
                    },
                    hints = listOf("Перенеси на завтра в 9", "Перенеси на час", "Перенеси на вечер"),
                    taskIds = listOfNotNull(task?.id).ifEmpty { ctx.lastTaskIds.take(1) },
                )
            }
            val task = resolveTask(parsed?.title?.takeIf { it.isNotBlank() } ?: body, ctx)
                ?: urgentTask(ctx)
            if (task == null) {
                return VoiceReply(
                    spoken = "Не нашёл такую задачу. Скажите «что на сегодня», и я перечислю.",
                    hints = DEFAULT_HINTS,
                )
            }
            val movedHasTime = (parsed?.hasTime ?: false) || hasPeriodWord(body)
            val whenText = RuTime.formatDue(millis, movedHasTime, ctx.now, ctx.zone).lowercase()
            return VoiceReply(
                spoken = "«${task.title}» перенесена на $whenText.",
                action = VoiceAction.MoveTask(task.id, task.title, millis, movedHasTime),
                hints = listOf("Что на сегодня?", "Что дальше?", "Отложи на 20 минут"),
                taskIds = listOf(task.id),
            )
        }

        // 17. Отложить
        val snoozeQuery = startsWithAny(n, SNOOZE_VERBS)
        if (snoozeQuery != null) {
            val minutes = parseMinutes(n)
            val query = stripSnoozeTail(stripCommandWords(text, SNOOZE_VERBS))
            val task = resolveTask(query, ctx)
            if (minutes == null) {
                val target = task ?: urgentTask(ctx)
                if (target == null) {
                    return VoiceReply("Нечего откладывать — задач нет.", hints = ADD_HINTS)
                }
                return VoiceReply(
                    spoken = "На сколько отложить «${target.title}»? Скажите, например, «на двадцать минут».",
                    hints = listOf("На 10 минут", "На 20 минут", "На час"),
                    taskIds = listOf(target.id),
                )
            }
            if (task == null) {
                val fallback = urgentTask(ctx)
                if (fallback == null) return VoiceReply("Нечего откладывать — задач нет.", hints = ADD_HINTS)
                return snoozeReply(fallback, minutes, ctx, seed)
            }
            return snoozeReply(task, minutes, ctx, seed)
        }

        // «на 20 минут» отдельной фразой после списка
        if (n.split(" ").size <= 5 && parseMinutes(n) != null &&
            (hasAnyWord(n, "минут", "минуты", "минуту", "час", "часа", "полчаса") || hasWord(n, "полчаса"))
        ) {
            val minutes = parseMinutes(n)
            val task = urgentTask(ctx)
            if (minutes != null && task != null) return snoozeReply(task, minutes, ctx, seed)
        }

        // 18. Добавить задачу
        val addQuery = startsWithAny(n, ADD_VERBS)
        if (addQuery != null) {
            return addTaskReply(text, ctx, seed)
        }

        // 19. Короткие «да/нет»
        if (n in setOf("да", "ага", "угу", "конечно", "хорошо", "нет", "не надо", "не нужно")) {
            return VoiceReply(spoken = if (n == "нет" || n.startsWith("не ")) "Хорошо." else "Отлично.", hints = DEFAULT_HINTS)
        }

        // 20. Вопрос без команды — уточняем
        if (startsWithAny(n, QUESTION_STARTS) != null && !looksLikeTask(n)) {
            return VoiceReply(
                spoken = "Не понял вопрос. Могу рассказать, что на сегодня, или записать задачу. Что сделать?",
                hints = DEFAULT_HINTS,
            )
        }

        // 21. Свободная фраза — считаем её задачей
        return addTaskReply(text, ctx, seed)
    }

    // ------------------------------------------------------------------ части ответов

    private fun addTaskReply(text: String, ctx: VoiceContext, seed: Int): VoiceReply {
        val body = stripAddPrefix(text)
        val parsed = runCatching { RuTimeParser.parse(digitsForTime(body), ctx.now, ctx.zone) }.getOrNull()
        val title = (parsed?.title ?: body).trim().trim('.', ',', '!', '?', ';', ':', '-', '—').trim()
        if (title.isBlank()) {
            return VoiceReply(
                spoken = "Что записать? Скажите, например: «напомни позвонить маме завтра в три».",
                hints = listOf("Напомни купить хлеб", "Что на сегодня?", "Что ты умеешь?"),
            )
        }

        // Место из фразы: «напомни вынести мусор дома»
        val bodyWords = WakeWord.normalize(body).split(" ").filter { it.isNotBlank() }
        val place = ctx.places.firstOrNull { place ->
            val name = WakeWord.normalize(place.name)
            name.isNotBlank() && bodyWords.any { word ->
                word == name || (word.startsWith(name) && word.length <= name.length + 3)
            }
        }

        val dueAt = parsed?.millis
        val hasTime = when {
            parsed == null -> true
            parsed.hasTime -> true
            hasPeriodWord(body) -> true
            else -> false
        }
        val action = VoiceAction.AddTask(
            title = RuTime.capitalize(title),
            dueAt = dueAt,
            hasTime = hasTime,
            placeId = place?.id,
            placeName = place?.name ?: "",
        )

        val speech = when {
            dueAt != null -> {
                val whenText = RuTime.formatDue(dueAt, hasTime, ctx.now, ctx.zone).lowercase()
                "${pick(seed, "Записано", "Готово", "Добавлено")}: «${RuTime.capitalize(title)}». Напомню $whenText."
            }
            place != null -> {
                "${pick(seed, "Записано", "Готово", "Добавлено")}: «${RuTime.capitalize(title)}». " +
                    "Напомню, когда будете в «${place.name}»."
            }
            else -> {
                "${pick(seed, "Записано", "Готово", "Добавлено")}: «${RuTime.capitalize(title)}». " +
                    "Когда напомнить? Можно сказать: «завтра в девять» или «через час»."
            }
        }

        val hints = if (dueAt == null && place == null) {
            listOf("Завтра в 9", "Сегодня в 19:00", "Через час")
        } else {
            listOf("Что на сегодня?", "Что дальше?", "Отложи на 20 минут")
        }
        return VoiceReply(spoken = speech, action = action, hints = hints)
    }

    private fun snoozeReply(task: Task, minutes: Int, ctx: VoiceContext, seed: Int): VoiceReply {
        val until = ctx.now + minutes * 60_000L
        val time = RuTime.formatTime(until, ctx.zone)
        val period = when {
            minutes % 60 == 0 && minutes >= 60 -> "${minutes / 60} ${plural(minutes / 60, "час", "часа", "часов")}"
            else -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")}"
        }
        return VoiceReply(
            spoken = pick(
                seed,
                "Хорошо, «${task.title}» — через $period, напомню в $time.",
                "Отложено: «${task.title}». Напомню в $time.",
            ),
            action = VoiceAction.SnoozeTask(task.id, task.title, minutes),
            hints = listOf("Что дальше?", "Выполни первую", "Что на сегодня?"),
            taskIds = listOf(task.id),
        )
    }

    private fun nextReply(ctx: VoiceContext, seed: Int): VoiceReply {
        val upcoming = ctx.tasks.filter { it.isActive && it.dueAt != null && it.dueAt!! >= ctx.now }
            .sortedBy { it.dueAt }
        val first = upcoming.firstOrNull()
            ?: return VoiceReply(
                spoken = "Дальше по времени задач нет. Можно отдохнуть.",
                hints = ADD_HINTS,
            )
        val more = if (upcoming.size > 1) " Потом — «${upcoming[1].title}»." else ""
        return VoiceReply(
            spoken = pick(
                seed,
                "Следующая — «${first.title}», ${untilText(first.dueAt!!, ctx)}.$more",
                "Дальше «${first.title}» — ${untilText(first.dueAt!!, ctx)}.$more",
            ),
            hints = listOf("Выполни первую", "Отложи на 20 минут", "Что на сегодня?"),
            taskIds = upcoming.take(5).map { it.id },
        )
    }

    private fun planReply(tasks: List<Task>, ctx: VoiceContext, day: String, seed: Int): VoiceReply {
        val sorted = tasks.filter { it.isActive }
            .sortedWith(compareBy<Task>({ it.dueAt ?: Long.MAX_VALUE }, { it.title }))
        if (sorted.isEmpty()) {
            return VoiceReply(
                spoken = pick(seed, "На $day задач нет. Свободно.", "На $day ничего не запланировано."),
                hints = ADD_HINTS,
            )
        }
        val shown = sorted.take(3)
        val head = "На $day ${plural(sorted.size, "задача", "задачи", "задач")}: ${sorted.size}."
        val body = shown.joinToString(". ") { describeTask(it, ctx) }
        val tail = if (sorted.size > shown.size) " И ещё ${sorted.size - shown.size}." else ""
        return VoiceReply(
            spoken = "$head $body.$tail",
            hints = listOf("Что дальше?", "Выполни первую", "Что просрочено"),
            taskIds = sorted.map { it.id },
        )
    }

    private fun describeTask(task: Task, ctx: VoiceContext): String {
        val whenText = task.dueAt?.let { RuTime.formatDue(it, task.hasTime, ctx.now, ctx.zone) }
        return when {
            whenText != null -> "$whenText — ${task.title}"
            task.placeId != null -> "По прибытии в «${task.placeName.ifBlank { "место" }}» — ${task.title}"
            else -> task.title
        }
    }

    private fun overdueNote(ctx: VoiceContext): String {
        val count = ctx.tasks.count { it.isActive && it.dueAt != null && it.dueAt < ctx.now }
        return if (count > 0) " Просрочено: $count." else ""
    }

    private fun notFound(query: String, ctx: VoiceContext, seed: Int, what: String): VoiceReply {
        val cleaned = query.trim()
        return if (cleaned.isBlank()) {
            VoiceReply(
                spoken = pick(seed, "Какую задачу $what?", "Уточните, какую задачу $what."),
                hints = listOf("Что на сегодня?", "Что просрочено"),
            )
        } else {
            VoiceReply(
                spoken = "Не нашёл задачу «$cleaned». Скажите «что на сегодня», и я перечислю задачи.",
                hints = DEFAULT_HINTS,
            )
        }
    }

    private fun retry(seed: Int, text: String): VoiceReply = VoiceReply(
        spoken = text,
        hints = DEFAULT_HINTS,
    )

    // ------------------------------------------------------------------ помощники

    private fun todayTasks(ctx: VoiceContext): List<Task> {
        val today = RuTime.dateOf(ctx.now, ctx.zone)
        return ctx.tasks.filter { it.isActive && it.dueAt != null && RuTime.dateOf(it.dueAt!!, ctx.zone) == today }
    }

    private fun urgentTask(ctx: VoiceContext): Task? {
        ctx.lastTaskIds.firstOrNull()?.let { id ->
            ctx.tasks.firstOrNull { it.id == id && it.isActive }?.let { return it }
        }
        val active = ctx.tasks.filter { it.isActive }
        return active.filter { it.dueAt != null }.minByOrNull { it.dueAt!! } ?: active.firstOrNull()
    }

    private fun resolveTask(query: String, ctx: VoiceContext): Task? {
        val ordered = ordinalIndex(query)
        if (ordered != null && ordered < ctx.lastTaskIds.size) {
            val id = ctx.lastTaskIds[ordered]
            ctx.tasks.firstOrNull { it.id == id && it.isActive }?.let { return it }
        }
        val q = WakeWord.normalize(query)
        if (q.isBlank() || isGeneric(q)) return null
        val active = ctx.tasks.filter { it.isActive }
        val qc = compact(q)
        if (qc.isBlank()) return null
        var best: Task? = null
        var bestScore = 0.0
        for (task in active) {
            val tc = compact(task.title)
            if (tc.isBlank()) continue
            val score = when {
                tc == qc -> 1.0
                tc.contains(qc) || qc.contains(tc) -> 0.9
                else -> 1.0 - levenshtein(qc, tc).toDouble() / maxOf(qc.length, tc.length)
            }
            if (score > bestScore) {
                bestScore = score
                best = task
            }
        }
        return if (bestScore >= 0.6) best else null
    }

    private fun isGeneric(query: String): Boolean {
        val words = WakeWord.normalize(query).split(" ").filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { it in GENERIC_TASK_WORDS }
    }

    private fun ordinalIndex(text: String): Int? {
        val n = WakeWord.normalize(text)
        val words = n.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val map = listOf(
            "перв" to 0, "втор" to 1, "трет" to 2, "четверт" to 3, "пят" to 4,
        )
        for ((stem, index) in map) {
            if (words.any { it.startsWith(stem) }) return index
        }
        val digit = words.firstOrNull { it.length == 1 && it[0].isDigit() }?.toIntOrNull()
        return digit?.minus(1)?.takeIf { it >= 0 }
    }

    /** «Завтра в 10» — уточнение времени, а не новая задача. */
    private fun timeOnly(text: String, ctx: VoiceContext): Pair<Long, Boolean>? {
        val parsed = runCatching { RuTimeParser.parse(digitsForTime(text), ctx.now, ctx.zone) }.getOrNull()
            ?: return null
        val millis = parsed.millis ?: return null
        val matched = parsed.matchedText ?: return null
        val covered = WakeWord.normalize(
            digitsForTime(text).replace(Regex("(?iuU)" + Regex.escape(matched)), " "),
        )
        val leftover = covered.split(" ").filter { it.isNotBlank() }
        val words = leftover.filter { word -> word.any { it.isLetter() } && word !in TIME_WORDS }
        if (words.isNotEmpty()) return null
        val hasTime = parsed.hasTime || leftover.any { it in PERIOD_WORDS } || hasPeriodWord(text)
        return millis to hasTime
    }

    private fun hasPeriodWord(text: String): Boolean =
        Regex("(?iuU)(утр|днем|дня|вечер|ноч|обед|полдень|полночь)").containsMatchIn(text)

    private val PERIOD_WORDS = setOf("утром", "утра", "днем", "дня", "вечером", "вечера", "ночью", "ночи", "обед", "полдень", "полночь")

    private val TIME_WORDS = PERIOD_WORDS + setOf(
        "в", "во", "к", "на", "около", "через", "с", "до", "после", "за",
        "час", "часа", "часов", "минут", "минуты", "минуту", "мин",
        "завтра", "сегодня", "послезавтра", "утро", "вечер", "ночь", "день",
    )

    private fun untilText(millis: Long, ctx: VoiceContext): String {
        val diff = millis - ctx.now
        return when {
            diff <= 0 -> "уже пора"
            diff < 60_000L -> "меньше чем через минуту"
            diff < 3_600_000L -> {
                val minutes = (diff / 60_000L).toInt()
                "через $minutes ${plural(minutes, "минуту", "минуты", "минут")}"
            }
            diff < 6 * 3_600_000L -> {
                val hours = (diff / 3_600_000L).toInt()
                "через $hours ${plural(hours, "час", "часа", "часов")}"
            }
            else -> RuTime.formatDue(millis, true, ctx.now, ctx.zone).lowercase()
        }
    }

    /**
     * Заменяет числительные цифрами там, где это время: «в девять» → «в 9»,
     * «через двадцать минут» → «через 20 минут». Остальной текст («купить два хлеба»)
     * не трогается, чтобы название задачи осталось человеческим.
     */
    fun digitsForTime(text: String): String {
        val pattern = Regex(
            "(?iuU)(?<![а-яё])(в|к|около|на|через|до|после|с)\\s+" +
                "((?:двадцать|тридцать|сорок|пятьдесят)\\s+)?" +
                "(один|одну|одна|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|" +
                "одиннадцать|двенадцать|тринадцать|четырнадцать|пятнадцать|шестнадцать|" +
                "семнадцать|восемнадцать|девятнадцать)(?![а-яё])",
        )
        return pattern.replace(text) { match ->
            val preposition = match.groupValues[1]
            val tens = match.groupValues[2].trim().lowercase()
            val unit = match.groupValues[3].lowercase()
            val value = (if (tens.isEmpty()) 0 else NUMBER_WORDS[tens] ?: 0) +
                (NUMBER_WORDS[unit] ?: 0)
            if (value <= 0) match.value else "$preposition $value"
        }
    }

    private fun parseMinutes(n: String): Int? {
        Regex("(?U)(?:на|через)\\s+(\\d{1,3})\\s*(?:минут|мин)\\w*").find(n)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        if (hasPhrase(n, "полтора часа") || hasPhrase(n, "полтора час")) return 90
        if (hasWord(n, "полчаса") || hasPhrase(n, "пол часа")) return 30
        Regex("(?U)(?:на|через)\\s+(\\d{1,2})\\s*час\\w*").find(n)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 1) * 60
        }
        Regex("(?U)(?:на|через)\\s+час\\b").find(n)?.let { return 60 }

        // «на двадцать минут», «через полтора часа» словами
        val words = n.split(" ").filter { it.isNotBlank() }
        for (i in words.indices) {
            if (words[i] != "на" && words[i] != "через") continue
            val rest = words.drop(i + 1)
            val unit = rest.indexOfFirst { it.startsWith("минут") || it.startsWith("мин") || it.startsWith("час") }
            if (unit <= 0) continue
            val number = verbalNumber(rest.take(unit)) ?: continue
            return if (rest[unit].startsWith("час")) number * 60 else number
        }
        return null
    }

    /** «двадцать» → 20, «двадцать пять» → 25. */
    private fun verbalNumber(words: List<String>): Int? {
        if (words.isEmpty()) return null
        var sum = 0
        for (word in words) {
            val value = NUMBER_WORDS[word] ?: return null
            sum += value
        }
        return sum.takeIf { it in 1..180 }
    }

    private val NUMBER_WORDS = mapOf(
        "один" to 1, "одну" to 1, "одна" to 1,
        "два" to 2, "две" to 2,
        "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7,
        "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16,
        "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19,
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40,
        "пятьдесят" to 50, "шестьдесят" to 60, "девяносто" to 90,
    )

    private fun stripSnoozeTail(text: String): String =
        text.replace(
            Regex(
                "(?iuU)\\s*(?:на|через)\\s*(?:\\d{1,3}\\s*(?:минут\\w*|мин|час\\w*)|полчаса|пол\\s+часа|полтора\\s+часа|час)\\s*$",
            ),
            "",
        ).trim()

    private fun stripCommandWords(text: String, verbs: List<String>): String {
        val pattern = "^(?iu)\\s*(?:пожалуйста[\\s,]*)?(?:" + verbPattern(verbs) +
            ")(?:\\s+(?:задачу|эту|это|последнюю))?\\s*[:,]?\\s*"
        return text.replaceFirst(Regex(pattern), "").trim()
    }

    private fun stripAddPrefix(text: String): String {
        val pattern = "^(?iu)\\s*(?:пожалуйста[\\s,]*)?(?:" + verbPattern(ADD_VERBS) +
            ")(?:\\s+(?:задачу|мне|пожалуйста))?\\s*[:,]?\\s*"
        var body = text.replaceFirst(Regex(pattern), "").trim()
        // «напомни мне, пожалуйста, ...» — снимаем ещё один слой вежливости
        body = body.replaceFirst(Regex("^(?iu)\\s*(?:мне|пожалуйста\\s*,?)\\s*"), "").trim()
        return body
    }

    /** Длинные формы («напомнить») должны идти раньше коротких («напомни»). */
    private fun verbPattern(verbs: List<String>): String =
        verbs.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private fun startsWithAny(n: String, prefixes: List<String>): String? =
        prefixes.firstOrNull { item -> n == item || n.startsWith("$item ") }

    private fun looksLikeTask(n: String): Boolean {
        val words = n.split(" ").filter { it.isNotBlank() }
        if (words.size <= 2) return false
        return words.any { it.length >= 6 }
    }

    private fun hasWord(n: String, word: String): Boolean = (" $n ").contains(" $word ")

    private fun hasPhrase(n: String, phrase: String): Boolean =
        (" $n ").contains(" ${WakeWord.normalize(phrase)} ")

    private fun hasAnyWord(n: String, vararg words: String): Boolean =
        n.split(" ").any { it in words }

    private fun hasPrefix(n: String, prefix: String): Boolean =
        n.split(" ").any { it.startsWith(prefix) }

    private fun compact(text: String): String = WakeWord.normalize(text).replace(" ", "")

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> one
            mod10 in 2..4 && mod100 !in 12..14 -> few
            else -> many
        }
    }

    private fun pick(seed: Int, vararg options: String): String =
        options[Math.floorMod(seed, options.size)]
}
