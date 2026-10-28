package com.zigorminsk.timemanager.util

import com.zigorminsk.timemanager.model.RepeatRule
import com.zigorminsk.timemanager.model.Task
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Форматирование дат по-русски.
 *
 * Модуль намеренно не зависит от Android, поэтому его можно прогонять юнит-тестами
 * на обычной JVM (`./gradlew :app:testDebugUnitTest`).
 */
object RuTime {

    val MONTHS_GEN = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    val WEEKDAYS_SHORT = arrayOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    val WEEKDAYS_NOM = arrayOf(
        "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье",
    )

    fun weekdayName(date: LocalDate): String = WEEKDAYS_NOM[date.dayOfWeek.value - 1]

    fun hhmm(hour: Int, minute: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    fun dayLabel(date: LocalDate, today: LocalDate): String = when {
        date == today -> "сегодня"
        date == today.plusDays(1) -> "завтра"
        date == today.minusDays(1) -> "вчера"
        date == today.plusDays(2) -> "послезавтра"
        date.year != today.year -> "${WEEKDAYS_SHORT[date.dayOfWeek.value - 1]}, " +
            "${date.dayOfMonth} ${MONTHS_GEN[date.monthValue - 1]} ${date.year}"
        else -> "${WEEKDAYS_SHORT[date.dayOfWeek.value - 1]}, " +
            "${date.dayOfMonth} ${MONTHS_GEN[date.monthValue - 1]}"
    }

    fun capitalize(text: String): String =
        if (text.isEmpty()) text else text[0].uppercase(Locale.ROOT) + text.substring(1)

    private fun zonedOrNull(millis: Long, zone: ZoneId): ZonedDateTime? =
        runCatching { Instant.ofEpochMilli(millis).atZone(zone) }.getOrNull()

    /** «Сегодня, 15:30» / «3 ноября, 09:00» / «3 ноября» (без времени). */
    fun formatDue(
        millis: Long,
        hasTime: Boolean,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val zdt = zonedOrNull(millis, zone) ?: return "—"
        val today = zonedOrNull(now, zone)?.toLocalDate() ?: LocalDate.now(zone)
        val dayText = capitalize(dayLabel(zdt.toLocalDate(), today))
        return if (hasTime) "$dayText, ${hhmm(zdt.hour, zdt.minute)}" else dayText
    }

    /** Полная дата «3 ноября 2026, 15:30» — для карточек и озвучки. */
    fun formatFull(millis: Long, hasTime: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val zdt = zonedOrNull(millis, zone) ?: return "—"
        val base = "${zdt.dayOfMonth} ${MONTHS_GEN[zdt.monthValue - 1]} ${zdt.year}"
        return if (hasTime) "$base, ${hhmm(zdt.hour, zdt.minute)}" else base
    }

    fun formatTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val zdt = zonedOrNull(millis, zone) ?: return "—"
        return hhmm(zdt.hour, zdt.minute)
    }

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

    fun combine(date: LocalDate, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    fun dateOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        zonedOrNull(millis, zone)?.toLocalDate() ?: LocalDate.now(zone)

    /** Следующее срабатывание повторяющейся задачи. */
    fun nextRepeatMillis(task: Task, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val due = task.dueAt ?: return null
        val zdt = zonedOrNull(due, zone) ?: return null
        val next: ZonedDateTime = when (task.repeat) {
            RepeatRule.NONE -> return null
            RepeatRule.DAILY -> zdt.plusDays(1)
            RepeatRule.WEEKDAYS -> {
                var n = zdt.plusDays(1)
                while (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) {
                    n = n.plusDays(1)
                }
                n
            }
            RepeatRule.WEEKLY -> zdt.plusWeeks(1)
            RepeatRule.MONTHLY -> zdt.plusMonths(1)
        }
        return next.toInstant().toEpochMilli()
    }

    /**
     * Дата и время так, как их произносит человек — без предлога:
     * «завтра в девять часов утра», «в пятницу в три тридцать дня», «двенадцатого ноября».
     */
    fun speakDue(
        millis: Long,
        hasTime: Boolean,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = RuSpeech.duePhrase(millis, hasTime, now, zone)

    /**
     * Озвучка одной задачи: короткие предложения с точками, полные слова,
     * правильные падежи. Синтезатор делает паузу на каждой точке, поэтому
     * заголовок больше не склеивается с датой в одну неразборчивую тираду.
     */
    fun speakTask(
        task: Task,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val parts = mutableListOf(RuSpeech.sentence(task.title.trimEnd('.', '!', '?')))
        task.dueAt?.let { due ->
            val whenText = speakDue(due, task.hasTime, now, zone)
            parts += RuSpeech.sentence(whenText)
        }
        if (task.placeId != null) {
            val place = task.placeName.ifBlank { "место" }
            parts += "Когда буду на месте — $place"
        }
        task.repeat.takeIf { it != RepeatRule.NONE }?.let { rule ->
            parts += when (rule) {
                RepeatRule.DAILY -> "Каждый день"
                RepeatRule.WEEKDAYS -> "По будням"
                RepeatRule.WEEKLY -> "Каждую неделю"
                RepeatRule.MONTHLY -> "Каждый месяц"
                RepeatRule.NONE -> ""
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString(". ") + "."
    }

    /** Текст «проговорить день» — числа словами и с правильным родом. */
    fun speakDay(
        tasks: List<Task>,
        placeTasks: List<Task>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val total = tasks.size + placeTasks.size
        if (total == 0) return "На сегодня задач нет. Отдыхайте!"

        val sb = StringBuilder()
        sb.append("На сегодня ").append(RuSpeech.count(total, "задача", "задачи", "задач")).append(". ")

        val overdue = tasks.filter { it.dueAt != null && it.dueAt < now }
        val actual = tasks.filter { it.dueAt == null || it.dueAt >= now }
        if (overdue.isNotEmpty()) {
            val one = overdue.size % 10 == 1 && overdue.size % 100 != 11
            val verb = if (one) "просрочена" else "просрочены"
            val head = "${RuSpeech.cardinal(overdue.size, true)} " +
                RuSpeech.plural(overdue.size, "задача", "задачи", "задач")
            sb.append(RuSpeech.sentence("$head $verb")).append(". ")
            overdue.forEach { sb.append(speakTask(it, now, zone)).append(" ") }
        }
        actual.forEach { sb.append(speakTask(it, now, zone)).append(" ") }
        placeTasks.forEach { sb.append(speakTask(it, now, zone)).append(" ") }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Разбор русской фразы: «завтра в 15:30 позвонить маме» → дата/время + чистый заголовок.
 */
object RuTimeParser {

    data class Parsed(
        val millis: Long?,
        val hasTime: Boolean,
        val title: String,
        val matchedText: String?,
        val relativeMinutes: Long? = null,
    )

    private const val PERIOD = "утра|утром|дня|днем|вечера|вечером|ночи|ночью|обед"
    private const val HOUR_WORD = "час(?:ов|а|ик|у)?"

    private val MONTH_STEMS = listOf(
        "январ" to 1, "феврал" to 2, "март" to 3, "апрел" to 4, "ма[йя]" to 5,
        "июн" to 6, "июл" to 7, "август" to 8, "сентябр" to 9, "октябр" to 10,
        "ноябр" to 11, "декабр" to 12,
    )

    private val WEEKDAYS = listOf(
        "понедельник" to 1, "вторник" to 2, "среду" to 3, "среды" to 3,
        "четверг" to 4, "пятницу" to 5, "пятницы" to 5, "субботу" to 6,
        "субботы" to 6, "воскресенье" to 7, "воскресенья" to 7,
    )

    private val NUMBER_WORD_VALUES = mapOf(
        "ноль" to 0, "один" to 1, "одну" to 1, "одна" to 1, "два" to 2, "две" to 2,
        "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7,
        "восемь" to 8, "девять" to 9, "десять" to 10, "одиннадцать" to 11,
        "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14,
        "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20,
        "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
    )

    private const val UNITS_WORDS = "один|одну|одна|два|две|три|четыре|пять|шесть|семь|восемь|" +
        "девять|десять|одиннадцать|двенадцать|тринадцать|четырнадцать|пятнадцать|" +
        "шестнадцать|семнадцать|восемнадцать|девятнадцать"

    /**
     * Заменяет числительные цифрами там, где речь о времени:
     * «в девять вечера» → «в 9 вечера», «через двадцать минут» → «через 20 минут»,
     * «минут через пятнадцать» → «минут через 15».
     * «купить два хлеба» остаётся словами — это часть названия задачи.
     */
    fun digitsForTime(text: String): String {
        // Сначала «час + минуты» словами: «в шесть тридцать» → «в 6:30».
        val numberWords = "(?:(?:двадцать|тридцать|сорок|пятьдесят)(?:\\s+(?:$UNITS_WORDS))?|(?:$UNITS_WORDS))"
        val timePair = Regex(
            "(?iuU)(?<![а-яё])(в|к|около|на)\\s+($numberWords)\\s+($numberWords)(?![а-яё])",
        )
        var out = timePair.replace(text) { match ->
            val preposition = match.groupValues[1]
            val hour = NUMBER_WORD_VALUES[match.groupValues[2].lowercase()]
            val minute = NUMBER_WORD_VALUES[match.groupValues[3].lowercase()]
            if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                "$preposition $hour:${minute.toString().padStart(2, '0')}"
            } else {
                match.value
            }
        }
        val withTens = Regex(
            "(?iuU)(?<![а-яё])(в|к|около|на|через|до|после|с)\\s+" +
                "(?:(двадцать|тридцать|сорок|пятьдесят)(?:\\s+($UNITS_WORDS))?|($UNITS_WORDS))" +
                "(?![а-яё])",
        )
        out = withTens.replace(out) { match ->
            val preposition = match.groupValues[1]
            val tens = match.groupValues[2].lowercase()
            val unitAfterTens = match.groupValues[3].lowercase()
            val unitAlone = match.groupValues[4].lowercase()
            val value = (NUMBER_WORD_VALUES[tens] ?: 0) +
                (NUMBER_WORD_VALUES[unitAfterTens] ?: 0) +
                (NUMBER_WORD_VALUES[unitAlone] ?: 0)
            if (value <= 0) match.value else "$preposition $value"
        }
        return out
    }

    fun parse(
        raw: String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Parsed {
        // Числительные словами — в цифры: «через пятнадцать минут» = «через 15 минут».
        // Диктовка и быстрый ввод приходят в разном виде, разбор должен понимать оба.
        // Важно: обе строки получаются из одного и того же текста, иначе индексы
        // найденных фрагментов разъедутся и вырезание испортит название задачи.
        val normalized = digitsForTime(raw)
        // Нижний регистр — посимвольно (1:1), чтобы индексы в work и out всегда совпадали:
        // некоторые символы (İ, ǅ…) при lowercase() меняют длину строки, и вырезание
        // найденных фрагментов падало с StringIndexOutOfBoundsException.
        var work = foldToLower(normalized)
        var out = normalized
        var matched: String? = null

        val nowZdt = Instant.ofEpochMilli(now).atZone(zone)
        val today = nowZdt.toLocalDate()

        var date: LocalDate? = null
        var hour: Int? = null
        var minute = 0
        var relativeMinutes: Long? = null
        var explicitTime = false
        var dayPeriodWord: String? = null
        var bareHour = false

        fun consume(m: MatchResult) {
            val len = m.range.last - m.range.first + 1
            if (matched == null) matched = out.substring(m.range)
            work = work.replaceRange(m.range, " ".repeat(len))
            out = out.replaceRange(m.range, " ".repeat(len))
        }

        fun handle(m: MatchResult, handler: (MatchResult) -> Unit) {
            consume(m)
            handler(m)
        }

        fun take(regex: Regex, handler: (MatchResult) -> Unit): Boolean {
            val m = regex.find(work) ?: return false
            handle(m, handler)
            return true
        }

        // ---------------- 1. Относительные интервалы ----------------
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+(полчаса|пол\\s+часа)")) { relativeMinutes = 30L; explicitTime = true }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+полтора\\s+часа")) { relativeMinutes = 90L; explicitTime = true }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+(минуту|минуту\\s+одну)")) { relativeMinutes = 1L; explicitTime = true }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+час(?!\\w)")) { relativeMinutes = 60L; explicitTime = true }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+четверть\\s+часа")) { relativeMinutes = 15L; explicitTime = true }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+(\\d+)[.,](\\d+)\\s*час(?:а|ов)?\\b")) {
                val hours = it.groupValues[1].toLongOrNull() ?: 0L
                val fraction = it.groupValues[2].toLongOrNull() ?: 0L
                val fractionMinutes = if (fraction < 10) fraction * 6 else fraction
                relativeMinutes = hours * 60L + fractionMinutes
                explicitTime = true
            }
        }
        // «минут через пятнадцать», «часа через два» — обратный порядок слов
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)минут(?:у|ы)?\\s+через\\s+(\\d{1,2})\\b")) {
                relativeMinutes = it.groupValues[1].toLongOrNull()
                explicitTime = true
            }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)час(?:а)?\\s+через\\s+(\\d{1,2})\\b")) {
                relativeMinutes = it.groupValues[1].toLongOrNull()?.times(60L)
                explicitTime = true
            }
        }
        // «полчаса», «через полчаса» без предлога
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)(?<![а-яё])пол\\s*часа(?![а-яё])")) { relativeMinutes = 30L; explicitTime = true }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+(\\d+)\\s*(?:минут[уы]?|мин)\\b")) {
                relativeMinutes = it.groupValues[1].toLongOrNull()?.times(1L)
                explicitTime = true
            }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+(\\d+)\\s*час(?:а|ов)?\\b")) {
                relativeMinutes = it.groupValues[1].toLongOrNull()?.times(60L)
                explicitTime = true
            }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+(\\d+)\\s*(?:недел[юи]|недель)\\b")) {
                date = today.plusWeeks(it.groupValues[1].toLongOrNull() ?: 1L)
            }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+(\\d+)\\s*(?:день|дня|дней|сутки|суток)\\b")) {
                date = today.plusDays(it.groupValues[1].toLongOrNull() ?: 1L)
            }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+неделю\\b")) { date = today.plusWeeks(1) }
        }
        if (relativeMinutes == null && date == null) {
            take(Regex("(?U)через\\s+месяц\\b")) { date = today.plusMonths(1) }
        }

        // ---------------- 2. Ключевые слова дат ----------------
        if (date == null) {
            take(Regex("(?U)\\bпослезавтра\\b")) { date = today.plusDays(2) }
        }
        if (date == null) {
            take(Regex("(?U)\\bсегодня\\b|\\bсегодняшн\\w*")) { date = today }
        }
        if (date == null) {
            take(Regex("(?U)\\bзавтра\\b|\\bзавтрашн\\w*")) { date = today.plusDays(1) }
        }

        // ---------------- 3. День недели ----------------
        if (date == null) {
            val alt = WEEKDAYS.joinToString("|") { it.first }
            val re = Regex("(?U)\\b(?:во?\\s+)?(след\\w*\\s+)?($alt)\\b")
            val m = re.find(work)
            if (m != null) {
                val word = m.groupValues[2]
                val dow = WEEKDAYS.firstOrNull { Regex(it.first).matches(word) }?.second
                if (dow != null) {
                    val explicitNext = m.groupValues[1].isNotBlank()
                    var target = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dow)))
                    if (explicitNext && target == today) target = target.plusWeeks(1)
                    date = target
                    handle(m) { }
                }
            }
        }

        // ---------------- 4. Явная дата: «3 ноября», «03.11.2026» ----------------
        if (date == null) {
            for ((stem, month) in MONTH_STEMS) {
                val m = Regex("(?U)(?<!\\d)(\\d{1,2})\\s+$stem\\w*(?:\\s+(\\d{4}))?").find(work)
                if (m != null) {
                    val day = m.groupValues[1].toIntOrNull() ?: 1
                    val year = m.groupValues[2].toIntOrNull() ?: nextYearFor(month, day, today)
                    val parsed = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                    if (parsed != null) {
                        date = parsed
                        handle(m) { }
                    }
                    break
                }
            }
        }
        if (date == null) {
            val m = Regex("(?U)(?<!\\d)(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{4}|\\d{2}))?(?!\\d)").find(work)
            if (m != null) {
                val day = m.groupValues[1].toIntOrNull() ?: 0
                val month = m.groupValues[2].toIntOrNull() ?: 0
                var year = m.groupValues[3].toIntOrNull()
                if (year != null && year < 100) year += 2000
                if (day in 1..31 && month in 1..12) {
                    val parsed = runCatching {
                        LocalDate.of(year ?: nextYearFor(month, day, today), month, day)
                    }.getOrNull()
                    if (parsed != null) {
                        date = parsed
                        handle(m) { }
                    }
                }
            }
        }
        if (date == null) {
            val m = Regex("(?U)(?<!\\d)(\\d{1,2})(?:-?го)?\\s+числа\\b").find(work)
            if (m != null) {
                val day = m.groupValues[1].toIntOrNull() ?: 0
                if (day in 1..31) {
                    var candidate = runCatching { today.withDayOfMonth(day) }.getOrNull()
                    if (candidate != null && candidate.isBefore(today)) candidate = candidate.plusMonths(1)
                    date = candidate
                    handle(m) { }
                }
            }
        }

        // ---------------- 5. Время ----------------
        if (hour == null) {
            take(Regex("(?U)в\\s+полдень\\b")) { hour = 12; minute = 0; explicitTime = true }
        }
        if (hour == null) {
            take(Regex("(?U)в\\s+полночь\\b")) { hour = 0; minute = 0; explicitTime = true }
        }

        // 5.1 «15:30», «в 15.30»
        if (hour == null) {
            val m = Regex(
                "(?U)(?<![\\d])(?:в|к|около)?\\s*(\\d{1,2})\\s*[:.]\\s*(\\d{2})" +
                    "(?![\\d])\\s*($PERIOD)?(?![а-яё])",
            ).find(work)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull() ?: -1
                val mi = m.groupValues[2].toIntOrNull() ?: -1
                if (h in 0..23 && mi in 0..59) {
                    hour = applyPeriod(h, m.groupValues[3])
                    minute = mi
                    explicitTime = true
                    handle(m) { }
                }
            }
        }

        // 5.2 «в 9 часов вечера», «к 15 часам»
        if (hour == null) {
            val m = Regex("(?U)(?:в|к|около)\\s+(\\d{1,2})\\s*$HOUR_WORD\\s*($PERIOD)?").find(work)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull()
                if (h != null && h in 0..23) {
                    hour = applyPeriod(h, m.groupValues[2])
                    minute = 0
                    explicitTime = true
                    handle(m) { }
                }
            }
        }

        // 5.3 «в 7 вечера», «к 9 утра»
        if (hour == null) {
            val m = Regex("(?U)(?:в|к|около)\\s+(\\d{1,2})\\s*($PERIOD)\\b").find(work)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull()
                if (h != null && h in 0..23) {
                    hour = applyPeriod(h, m.groupValues[2])
                    minute = 0
                    explicitTime = true
                    handle(m) { }
                }
            }
        }

        // 5.4 «7 вечера» без предлога
        if (hour == null) {
            val m = Regex("(?U)(?<!\\d)(\\d{1,2})\\s+($PERIOD)\\b").find(work)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull()
                if (h != null && h in 0..23) {
                    hour = applyPeriod(h, m.groupValues[2])
                    minute = 0
                    explicitTime = true
                    handle(m) { }
                }
            }
        }

        // 5.5 голое «в 7» в конце фразы: «позвонить маме в 7», «в 7.»
        if (hour == null) {
            val m = Regex("(?U)(?:^|[\\s,;:(])(?:в|к)\\s+(\\d{1,2})(?=\\s*(?:\\z|[,.!?;:]))").find(work)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull()
                if (h != null && h in 0..23) {
                    hour = h
                    minute = 0
                    explicitTime = true
                    bareHour = true
                    handle(m) { }
                }
            }
        }

        // 5.6 «утром», «вечером» без числа
        if (hour == null) {
            val m = Regex("(?U)\\b($PERIOD)\\b").find(work)
            if (m != null) {
                dayPeriodWord = m.groupValues[1]
                handle(m) { }
            }
        }

        // ---------------- 6. Сборка ----------------
        var millis: Long? = null
        var hasTime = false
        if (relativeMinutes != null) {
            millis = now + relativeMinutes!! * 60_000L
            hasTime = true
        } else if (date != null || hour != null || dayPeriodWord != null) {
            val targetHour = when {
                hour != null -> hour ?: 9
                dayPeriodWord?.startsWith("утр") == true -> 9
                dayPeriodWord?.startsWith("дн") == true || dayPeriodWord?.startsWith("обед") == true -> 13
                dayPeriodWord?.startsWith("вечер") == true -> 19
                else -> 9
            }
            val targetMinute = if (hour != null) minute else 0
            var effectiveHour = targetHour
            // «в 7» без даты и части суток: если 7 утра уже прошло — скорее всего имелось в виду 19:00
            if (bareHour && date == null && effectiveHour in 1..11) {
                val morning = LocalDateTime.of(today, LocalTime.of(effectiveHour, targetMinute))
                val evening = morning.plusHours(12)
                if (!morning.isAfter(nowZdt.toLocalDateTime()) && evening.isAfter(nowZdt.toLocalDateTime())) {
                    effectiveHour += 12
                }
            }
            var ldt = LocalDateTime.of(date ?: today, LocalTime.of(effectiveHour, targetMinute))
            if (date == null && !ldt.isAfter(nowZdt.toLocalDateTime())) {
                ldt = ldt.plusDays(1)
            }
            millis = ldt.atZone(zone).toInstant().toEpochMilli()
            hasTime = explicitTime
        }

        // ---------------- 7. Чистка названия ----------------
        var title = out.replace(Regex("(?U)\\s+"), " ").trim()
        val filler = Regex(
            "(?U)^(напомни(ть|те)?|не забудь(те)?|не забыть|пожалуйста|мне|поставь задачу|создай задачу|задача)\\b[\\s,.:!—–-]*",
            RegexOption.IGNORE_CASE,
        )
        while (true) {
            val next = title.replaceFirst(filler, "")
            if (next == title) break
            title = next
        }
        title = title
            .replace(Regex("(?U)^[\\s,.;:!?—–-]+"), "")
            .replace(Regex("(?U)\\s+"), " ")
            .replace(Regex("(?U)\\s+(в|во|к|на|и|по)$"), "")
            .replace(Regex("(?U)[\\s,.;:!?—–-]+$"), "")
            .trim()
        if (title.isBlank()) title = normalized.trim()

        return Parsed(
            millis = millis,
            hasTime = hasTime,
            title = RuTime.capitalize(title),
            matchedText = matched?.replace(Regex("(?U)\\s+"), " ")?.trim()?.ifBlank { null },
            relativeMinutes = relativeMinutes,
        )
    }

    /** Посимвольное приведение к нижнему регистру с сохранением длины строки. */
    private fun foldToLower(text: String): String {
        val chars = CharArray(text.length)
        for (i in text.indices) {
            val c = text[i]
            chars[i] = when (c) {
                'ё', 'Ё' -> 'е'
                else -> c.lowercaseChar()
            }
        }
        return String(chars)
    }

    private fun nextYearFor(month: Int, day: Int, today: LocalDate): Int {
        val candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
        return if (candidate == null || candidate.isBefore(today)) today.year + 1 else today.year
    }

    /** Подставляет часть суток к 12-часовому формату: «7 вечера» → 19. */
    fun applyPeriod(hour: Int, period: String): Int {
        val p = period.trim()
        if (p.isEmpty()) return hour
        return when {
            p.startsWith("утр") -> if (hour == 12) 0 else hour
            p.startsWith("дн") || p.startsWith("обед") -> if (hour in 1..11) hour + 12 else hour
            p.startsWith("вечер") -> if (hour in 1..11) hour + 12 else hour
            p.startsWith("ноч") -> when {
                hour == 12 -> 0
                hour in 7..11 -> hour + 12
                else -> hour
            }
            else -> hour
        }
    }
}
