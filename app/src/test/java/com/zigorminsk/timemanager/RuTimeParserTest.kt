package com.zigorminsk.timemanager

import com.zigorminsk.timemanager.model.RepeatRule
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.util.RuTime
import com.zigorminsk.timemanager.util.RuTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RuTimeParserTest {

    private val zone: ZoneId = ZoneId.of("Europe/Minsk")

    /** Вторник, 27 октября 2026, 10:00. */
    private val now: Long = LocalDateTime.of(2026, 10, 27, 10, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private fun parse(text: String) = RuTimeParser.parse(text, now, zone)

    private fun assertAt(expected: String?, text: String) {
        val p = parse(text)
        val actual = p.millis?.let {
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), zone).toString()
        }
        assertEquals("фраза: $text", expected?.let { LocalDateTime.parse(it).toString() }, actual)
    }

    @Test
    fun `время с двоеточием`() {
        val p = parse("позвонить маме в 15:30")
        assertEquals("2026-10-27T15:30", LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(p.millis!!), zone).toString())
        assertEquals("Позвонить маме", p.title)
        assertTrue(p.hasTime)
    }

    @Test
    fun `завтра утром`() = assertAt("2026-10-28T09:00", "завтра в 9 утра купить хлеб")

    @Test
    fun `через тридцать минут`() = assertAt("2026-10-27T10:30", "через 30 минут выключить плиту")

    @Test
    fun `через час`() = assertAt("2026-10-27T11:00", "через час позвонить")

    @Test
    fun `через два часа`() = assertAt("2026-10-27T12:00", "через 2 часа набрать воду")

    @Test
    fun `день недели`() = assertAt("2026-10-30T18:00", "в пятницу в 18:00 встреча с другом")

    @Test
    fun `следующий понедельник`() = assertAt("2026-11-02T10:00", "в понедельник в 10:00 планёрка")

    @Test
    fun `месяц словом`() = assertAt("2026-11-03T12:00", "3 ноября в 12:00 стоматолог")

    @Test
    fun `месяц словом со годом`() = assertAt("2027-01-01T00:30", "1 января 2027 в 00:30 салют")

    @Test
    fun `дата цифрами`() = assertAt("2026-12-25T10:00", "25.12 в 10:00 купить подарки")

    @Test
    fun `вечером без числа`() = assertAt("2026-10-27T19:00", "напомни вечером позвонить маме")

    @Test
    fun `семь вечера`() = assertAt("2026-10-27T19:00", "в 7 вечера тренировка")

    @Test
    fun `одиннадцать ночи`() = assertAt("2026-10-27T23:00", "в 11 ночи спать")

    @Test
    fun `через два дня`() = assertAt("2026-10-29T15:00", "через 2 дня в 15:00 сдать отчет")

    @Test
    fun `послезавтра в полдень`() = assertAt("2026-10-29T12:00", "послезавтра в полдень обед с шефом")

    @Test
    fun `только дата - время не важно`() {
        val p = parse("в субботу уборка")
        assertEquals("2026-10-31T09:00", LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(p.millis!!), zone).toString())
        assertFalse(p.hasTime)
    }

    @Test
    fun `голый час без даты - вечер`() = assertAt("2026-10-27T19:00", "позвонить в 7")

    @Test
    fun `нет времени - просто текст`() {
        val p = parse("купить дрель когда приеду домой")
        assertNull(p.millis)
        assertEquals("Купить дрель когда приеду домой", p.title)
    }

    @Test
    fun `число магазинов не превращается во время`() {
        val p = parse("сходить в 5 магазинов")
        assertNull(p.millis)
    }

    @Test
    fun `время вырезается из названия`() {
        val p = parse("купить хлеб в 15:30 и молоко")
        assertEquals("Купить хлеб и молоко", p.title)
    }

    @Test
    fun `филлеры удаляются`() {
        assertEquals("Позвонить врачу", parse("не забудь позвонить врачу завтра в 11").title)
    }

    @Test
    fun `к 15 00`() = assertAt("2026-10-27T15:00", "к 15:00 быть в офисе")

    @Test
    fun `не падает на необычных символах`() {
        val phrases = listOf(
            "İstanbul завтра в 10:00",
            "ǅревний",
            "Straße",
            "напомни завтра в 9 утра İ",
            "🚀 через 30 минут",
            "1.2.3.4.5",
            "в 25:99",
            "31 февраля",
            "İİİİ",
            "",
            " ",
            ",.;:!?-",
        )
        phrases.forEach { phrase ->
            val p = RuTimeParser.parse(phrase, now, zone)
            // Главное — не исключение; заголовок не должен «съедаться» целиком
            assertTrue(phrase.isBlank() || p.title.isNotBlank())
        }
    }

    @Test
    fun `повтор каждый день`() {
        val task = Task(
            title = "зарядка",
            dueAt = now,
            repeat = RepeatRule.DAILY,
        )
        val next = RuTime.nextRepeatMillis(task, zone)!!
        assertEquals(
            LocalDateTime.parse("2026-10-28T10:00"),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone),
        )
    }

    @Test
    fun `повтор по будням прыгает через выходные`() {
        val friday = LocalDateTime.of(2026, 10, 30, 9, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val task = Task(title = "планёрка", dueAt = friday, repeat = RepeatRule.WEEKDAYS)
        val next = RuTime.nextRepeatMillis(task, zone)!!
        assertEquals(
            LocalDateTime.parse("2026-11-02T09:00"),
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone),
        )
    }
}
