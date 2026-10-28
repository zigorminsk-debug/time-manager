package com.zigorminsk.timemanager

import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.model.RepeatRule
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.util.RuSpeech
import com.zigorminsk.timemanager.util.RuTime
import com.zigorminsk.timemanager.util.RuTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Проверки «человеческой» озвучки: числа словами, падежи, род, отсутствие
 * сокращений и склейки времени в «пятнадцать 30».
 */
class VoiceSpeechTest {

    private val zone: ZoneId = ZoneId.of("Europe/Minsk")

    /** Вторник, 27 октября 2026, 10:00. */
    private val now: Long = LocalDateTime.of(2026, 10, 27, 10, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    private fun on(month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, month, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------- числа словами

    @Test
    fun `количественные числительные с родом`() {
        assertEquals("ноль", RuSpeech.cardinal(0))
        assertEquals("один", RuSpeech.cardinal(1))
        assertEquals("одна", RuSpeech.cardinal(1, feminine = true))
        assertEquals("две", RuSpeech.cardinal(2, feminine = true))
        assertEquals("двадцать один", RuSpeech.cardinal(21))
        assertEquals("сорок пять", RuSpeech.cardinal(45))
        assertEquals("девяносто девять", RuSpeech.cardinal(99))
    }

    @Test
    fun `счёт с правильным падежом`() {
        assertEquals("одна задача", RuSpeech.count(1, "задача", "задачи", "задач"))
        assertEquals("две задачи", RuSpeech.count(2, "задача", "задачи", "задач"))
        assertEquals("пять задач", RuSpeech.count(5, "задача", "задачи", "задач"))
        assertEquals("одиннадцать задач", RuSpeech.count(11, "задача", "задачи", "задач"))
        assertEquals("двадцать одна задача", RuSpeech.count(21, "задача", "задачи", "задач"))
    }

    @Test
    fun `время словами с частью суток`() {
        assertEquals("девять часов утра", RuSpeech.clock(9, 0))
        assertEquals("девять ноль пять утра", RuSpeech.clock(9, 5))
        assertEquals("полдень", RuSpeech.clock(12, 0))
        assertEquals("час дня", RuSpeech.clock(13, 0))
        assertEquals("два часа дня", RuSpeech.clock(14, 0))
        assertEquals("три тридцать дня", RuSpeech.clock(15, 30))
        assertEquals("шесть сорок пять вечера", RuSpeech.clock(18, 45))
        assertEquals("одиннадцать часов вечера", RuSpeech.clock(23, 0))
        assertEquals("полночь", RuSpeech.clock(0, 0))
        assertEquals("двенадцать тридцать ночи", RuSpeech.clock(0, 30))
    }

    @Test
    fun `даты в правильном падеже`() {
        val today = LocalDateTime.of(2026, 10, 27, 10, 0).toLocalDate()
        assertEquals("сегодня", RuSpeech.dayPhrase(today, today))
        assertEquals("завтра", RuSpeech.dayPhrase(today.plusDays(1), today))
        assertEquals("послезавтра", RuSpeech.dayPhrase(today.plusDays(2), today))
        assertEquals("в пятницу", RuSpeech.dayPhrase(today.plusDays(3), today))
        assertEquals("в понедельник", RuSpeech.dayPhrase(today.plusDays(6), today))
        assertEquals("третьего ноября", RuSpeech.dayPhrase(today.plusDays(7), today))
        assertEquals("двадцать первого декабря", RuSpeech.dayPhrase(today.plusDays(55), today))
    }

    @Test
    fun `фраза о сроке без цифр и сокращений`() {
        assertEquals("завтра в девять часов утра", RuTime.speakDue(at(28, 9, 0), true, now, zone))
        assertEquals("сегодня в три тридцать дня", RuTime.speakDue(at(27, 15, 30), true, now, zone))
        assertEquals("в пятницу в шесть сорок пять вечера", RuTime.speakDue(at(30, 18, 45), true, now, zone))
        assertEquals("завтра", RuTime.speakDue(at(28, 9, 0), false, now, zone))
        assertEquals("третьего ноября в десять часов утра", RuTime.speakDue(on(11, 3, 10, 0), true, now, zone))
    }

    @Test
    fun `относительное время словами`() {
        assertEquals("через двадцать минут", RuSpeech.relativePhrase(now + 20 * 60_000L, now))
        assertEquals("через час", RuSpeech.relativePhrase(now + 60 * 60_000L, now))
        assertEquals("через три часа", RuSpeech.relativePhrase(now + 180 * 60_000L, now))
        assertEquals("через час тридцать минут", RuSpeech.relativePhrase(now + 90 * 60_000L, now))
    }

    // ------------------------------------------------------------- задача целиком

    @Test
    fun `задача проговаривается предложениями без цифр`() {
        val task = Task(
            title = "Позвонить маме",
            dueAt = at(28, 9, 0),
            hasTime = true,
        )
        assertEquals("Позвонить маме. Завтра в девять часов утра.", RuTime.speakTask(task, now, zone))
    }

    @Test
    fun `название с «ё» не калечится`() {
        val task = Task(title = "Позвонить Лёше", dueAt = at(28, 19, 0), hasTime = true)
        assertEquals("Позвонить Лёше. Завтра в семь часов вечера.", RuTime.speakTask(task, now, zone))
    }

    @Test
    fun `задача по месту и с повтором`() {
        val task = Task(
            title = "Полить цветы",
            placeId = "p1",
            placeName = "Дом",
            repeat = RepeatRule.DAILY,
        )
        assertEquals(
            "Полить цветы. Когда буду на месте — Дом. Каждый день.",
            RuTime.speakTask(task, now, zone),
        )
    }

    @Test
    fun `день целиком — числа словами`() {
        val tasks = listOf(
            Task(title = "Позвонить маме", dueAt = at(28, 9, 0), hasTime = true),
            Task(title = "Купить хлеб", dueAt = at(28, 18, 0), hasTime = true),
        )
        val text = RuTime.speakDay(tasks, emptyList(), now, zone)
        assertTrue(text, text.startsWith("На сегодня две задачи."))
        assertTrue(text, text.contains("Позвонить маме. Завтра в девять часов утра."))
        assertTrue(text, text.contains("Купить хлеб. Завтра в шесть часов вечера."))
        assertTrue("в тексте не должно быть двоеточий-сокращений: $text", !text.contains("Задач на сегодня:"))
    }

    @Test
    fun `просроченная одна задача — просрочена`() {
        val tasks = listOf(
            Task(title = "Отправить отчёт", dueAt = at(26, 18, 0), hasTime = true),
        )
        val text = RuTime.speakDay(tasks, emptyList(), now, zone)
        assertTrue(text, text.contains("Одна задача просрочена."))
        assertTrue(text, text.contains("Отправить отчёт. Вчера в шесть часов вечера."))
    }

    @Test
    fun `пять задач — задач`() {
        val tasks = (1..5).map { Task(title = "Дело $it", dueAt = at(28, 9 + it, 0), hasTime = true) }
        assertTrue(RuTime.speakDay(tasks, emptyList(), now, zone).startsWith("На сегодня пять задач."))
    }

    @Test
    fun `пустой день`() {
        assertEquals("На сегодня задач нет. Отдыхайте!", RuTime.speakDay(emptyList(), emptyList(), now, zone))
    }

    // ------------------------------------------------------------- диктовка времени

    @Test
    fun `надиктованное время цифрами`() {
        val p = RuTimeParser.parse("Через 15 минут", now, zone)
        assertEquals(now + 15 * 60_000L, p.millis)
        assertTrue(p.hasTime)
    }

    @Test
    fun `надиктованное время словами`() {
        val p = RuTimeParser.parse("через пятнадцать минут", now, zone)
        assertEquals(now + 15 * 60_000L, p.millis)
    }

    @Test
    fun `время в обратном порядке слов`() {
        val p = RuTimeParser.parse("минут через пятнадцать", now, zone)
        assertEquals(now + 15 * 60_000L, p.millis)
    }

    @Test
    fun `полчаса и полтора часа`() {
        assertEquals(now + 30 * 60_000L, RuTimeParser.parse("через полчаса", now, zone).millis)
        assertEquals(now + 90 * 60_000L, RuTimeParser.parse("через полтора часа", now, zone).millis)
        assertEquals(now + 30 * 60_000L, RuTimeParser.parse("полчаса", now, zone).millis)
        assertEquals(now + 90 * 60_000L, RuTimeParser.parse("через 1,5 часа", now, zone).millis)
    }

    @Test
    fun `надиктованное «завтра в девять»`() {
        val p = RuTimeParser.parse("Завтра в девять", now, zone)
        assertEquals(at(28, 9, 0), p.millis)
        assertTrue(p.hasTime)
    }

    @Test
    fun `надиктованное «в пятницу в шесть вечера»`() {
        val p = RuTimeParser.parse("В пятницу в шесть вечера", now, zone)
        assertEquals(at(30, 18, 0), p.millis)
        assertTrue(p.hasTime)
    }

    @Test
    fun `надиктованное «в шесть тридцать вечера»`() {
        val p = RuTimeParser.parse("в шесть тридцать вечера", now, zone)
        assertEquals(at(27, 18, 30), p.millis)
        assertTrue(p.hasTime)
    }

    @Test
    fun `надиктованное «в двадцать тридцать»`() {
        val p = RuTimeParser.parse("в двадцать тридцать", now, zone)
        assertEquals(at(27, 20, 30), p.millis)
    }

    @Test
    fun `длительность в названии не превращается во время`() {
        val p = RuTimeParser.parse("погулять два часа", now, zone)
        assertEquals("Погулять два часа", p.title)
        assertNull(p.millis)
    }

    @Test
    fun `название не портится цифрами`() {
        val p = RuTimeParser.parse("купить два хлеба и три яйца", now, zone)
        assertEquals("Купить два хлеба и три яйца", p.title)
    }

    @Test
    fun `место не влияет на разбор`() {
        val place = Place(name = "Работа")
        assertEquals("Работа", place.name)
    }
}
