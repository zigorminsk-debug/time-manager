package com.zigorminsk.timemanager.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Текст для синтезатора речи: числа словами, правильные падежи и род.
 *
 * Синтезатор Android (Google ru-RU) читает цифры и сокращения по-своему:
 * «15:30» превращается в «пятнадцать тридцать», «пн» — в «пэ-эн», «3 ноября» —
 * в «три ноября» без падежа. Поэтому всё, что уходит в динамик, собирается
 * здесь словами: «в пятницу в три тридцать дня», «две задачи просрочены».
 *
 * Модуль не зависит от Android, чтобы его можно было проверять юнит-тестами.
 */
object RuSpeech {

    private val UNITS_MASC = arrayOf(
        "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
        "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )

    private val UNITS_FEM = UNITS_MASC.copyOf().also { it[1] = "одна"; it[2] = "две" }

    private val TENS = arrayOf(
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят",
        "шестьдесят", "семьдесят", "восемьдесят", "девяносто",
    )

    private val WEEKDAYS_ACC = arrayOf(
        "в понедельник", "во вторник", "в среду", "в четверг",
        "в пятницу", "в субботу", "в воскресенье",
    )

    private val ORDINAL_STEMS = arrayOf(
        "перв", "втор", "трет", "четверт", "пят", "шест", "седьм", "восьм", "девят",
        "десят", "одиннадцат", "двенадцат", "тринадцат", "четырнадцат", "пятнадцат",
        "шестнадцат", "семнадцат", "восемнадцат", "девятнадцат", "двадцат",
        "двадцать перв", "двадцать втор", "двадцать трет", "двадцать четверт",
        "двадцать пят", "двадцать шест", "двадцать седьм", "двадцать восьм",
        "двадцать девят", "тридцат", "тридцать перв",
    )

    /** Количественное числительное 0..99 словами. */
    fun cardinal(n: Int, feminine: Boolean = false): String {
        val value = n.coerceIn(0, 99)
        val units = if (feminine) UNITS_FEM else UNITS_MASC
        return when {
            value < 20 -> units[value]
            value % 10 == 0 -> TENS[value / 10]
            else -> "${TENS[value / 10]} ${units[value % 10]}"
        }
    }

    /** «одна задача», «две задачи», «пять задач». */
    fun count(n: Int, one: String, few: String, many: String): String =
        "${cardinal(n, feminine = true)} ${plural(n, one, few, many)}"

    /** Порядковое числительное в родительном падеже: «третьего», «двадцать первого». */
    fun ordinalGenitive(day: Int): String {
        val index = day.coerceIn(1, 31) - 1
        val stem = ORDINAL_STEMS[index]
        return when {
            day == 3 -> "третьего"
            day == 31 -> "тридцать первого"
            else -> stem + "ого"
        }
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    /**
     * Время словами без предлога: «девять часов утра», «три тридцать дня», «полдень».
     * Часы 13..23 переводятся в 12-часовой формат с частью суток — так говорят люди.
     */
    fun clock(hour24: Int, minute: Int): String {
        val hour = ((hour24 % 24) + 24) % 24
        val minuteSafe = minute.coerceIn(0, 59)
        if (hour == 12 && minuteSafe == 0) return "полдень"
        if (hour == 0 && minuteSafe == 0) return "полночь"

        val part = when (hour) {
            in 0..4 -> "ночи"
            in 5..11 -> "утра"
            in 12..16 -> "дня"
            else -> "вечера"
        }
        val hour12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val minutesText = when {
            minuteSafe == 0 -> ""
            minuteSafe < 10 -> "ноль ${cardinal(minuteSafe)}"
            else -> cardinal(minuteSafe)
        }
        val hourText = when {
            minuteSafe != 0 -> if (hour12 == 1) "час" else cardinal(hour12)
            hour12 == 1 -> "час"
            else -> "${cardinal(hour12)} ${plural(hour12, "час", "часа", "часов")}"
        }
        return listOf(hourText, minutesText, part)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /** Дата словами: «сегодня», «завтра», «в пятницу», «двенадцатого ноября». */
    fun dayPhrase(date: LocalDate, today: LocalDate): String {
        val diff = ChronoUnit.DAYS.between(today, date)
        return when {
            diff == 0L -> "сегодня"
            diff == 1L -> "завтра"
            diff == 2L -> "послезавтра"
            diff == -1L -> "вчера"
            diff == -2L -> "позавчера"
            diff in 3L..6L -> WEEKDAYS_ACC[date.dayOfWeek.value - 1]
            diff in -6L..-3L -> WEEKDAYS_ACC[date.dayOfWeek.value - 1]
            else -> "${ordinalGenitive(date.dayOfMonth)} " +
                RuTime.MONTHS_GEN[date.monthValue - 1]
        }
    }

    /**
     * Дата и время одной фразой без предлога:
     * «завтра в девять часов утра», «в пятницу в три тридцать дня», «двенадцатого ноября».
     */
    fun duePhrase(
        millis: Long,
        hasTime: Boolean,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val at = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = dayPhrase(at.toLocalDate(), today)
        return if (hasTime) "$day в ${clock(at.hour, at.minute)}" else day
    }

    /** «Через двадцать минут», «через час», «через три часа» — для отложенных напоминаний. */
    fun relativePhrase(millis: Long, now: Long): String {
        val diff = millis - now
        if (diff <= 0) return "уже пора"
        val minutes = ((diff + 30_000L) / 60_000L).toInt()
        return when {
            minutes <= 0 -> "меньше чем через минуту"
            minutes < 60 -> "через ${count(minutes, "минуту", "минуты", "минут")}"
            minutes % 60 == 0 && minutes / 60 < 24 ->
                "через ${count(minutes / 60, "час", "часа", "часов")}"
            minutes < 24 * 60 -> {
                val h = minutes / 60
                val m = minutes % 60
                "через ${count(h, "час", "часа", "часов")} ${cardinal(m)} " +
                    plural(m, "минуту", "минуты", "минут")
            }
            else -> ""
        }
    }

    /** Первая буква заглавной, остальное как есть. */
    fun sentence(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        return trimmed.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }
}
