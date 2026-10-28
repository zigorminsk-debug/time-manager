package com.zigorminsk.timemanager

import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.voice.VoiceAction
import com.zigorminsk.timemanager.voice.VoiceBrain
import com.zigorminsk.timemanager.voice.VoiceContext
import com.zigorminsk.timemanager.voice.VoiceReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class VoiceBrainTest {

    private val zone: ZoneId = ZoneId.of("Europe/Minsk")

    /** Вторник, 27 октября 2026, 10:00. */
    private val now: Long = LocalDateTime.of(2026, 10, 27, 10, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val callMom = Task(id = "1", title = "Позвонить маме", dueAt = at(27, 15, 30))
    private val buyBread = Task(id = "2", title = "Купить хлеб", dueAt = at(27, 18, 0))
    private val report = Task(id = "3", title = "Сдать отчёт", dueAt = at(26, 12, 0))
    private val home = Place(id = "p1", name = "Дом", wifiNames = listOf("Home"))

    private fun context(
        tasks: List<Task> = listOf(callMom, buyBread, report),
        places: List<Place> = listOf(home),
        lastTaskIds: List<String> = listOf("1", "2"),
        pendingTaskId: String? = null,
        pendingTitle: String = "",
    ) = VoiceContext(
        now = now,
        zone = zone,
        tasks = tasks,
        places = places,
        lastTaskIds = lastTaskIds,
        pendingTaskId = pendingTaskId,
        pendingTitle = pendingTitle,
    )

    private fun reply(text: String, ctx: VoiceContext = context()): VoiceReply =
        VoiceBrain.handle(text, ctx)

    @Test
    fun `план на сегодня`() {
        val reply = reply("Что у меня сегодня?")
        assertNull(reply.action)
        assertTrue(reply.spoken.contains("Позвонить маме"))
        assertTrue(reply.spoken.contains("Купить хлеб"))
        assertTrue(reply.taskIds.containsAll(listOf("1", "2")))
        assertTrue(reply.keepListening)
    }

    @Test
    fun `новая задача с датой`() {
        val reply = reply("Напомни позвонить папе завтра в три часа дня")
        val action = reply.action
        assertTrue(action is VoiceAction.AddTask)
        action as VoiceAction.AddTask
        assertEquals("Позвонить папе", action.title)
        assertEquals(at(28, 15), action.dueAt)
        assertTrue(action.hasTime)
        assertTrue(reply.spoken.contains("Напомню"))
    }

    @Test
    fun `новая задача без времени`() {
        val reply = reply("Напомни купить молоко")
        val action = reply.action as VoiceAction.AddTask
        assertEquals("Купить молоко", action.title)
        assertNull(action.dueAt)
        assertTrue(reply.spoken.contains("Когда напомнить"))
    }

    @Test
    fun `уточнение времени для новой задачи`() {
        val reply = reply(
            "завтра в девять",
            context(pendingTaskId = "new", pendingTitle = "Купить молоко"),
        )
        val action = reply.action
        assertTrue(action is VoiceAction.SetDue)
        action as VoiceAction.SetDue
        assertEquals("new", action.taskId)
        assertEquals(at(28, 9), action.dueAt)
    }

    @Test
    fun `новая задача по месту`() {
        val reply = reply("Напомни вынести мусор дома")
        val action = reply.action as VoiceAction.AddTask
        assertEquals("p1", action.placeId)
        assertTrue(reply.spoken.contains("Дом"))
    }

    @Test
    fun `отложить задачу`() {
        val reply = reply("Отложи купить хлеб на 20 минут")
        val action = reply.action
        assertTrue(action is VoiceAction.SnoozeTask)
        action as VoiceAction.SnoozeTask
        assertEquals("2", action.taskId)
        assertEquals(20, action.minutes)
    }

    @Test
    fun `отложить самую срочную`() {
        val reply = reply("Отложи на полчаса")
        val action = reply.action as VoiceAction.SnoozeTask
        assertEquals(30, action.minutes)
        assertNotNull(action.taskId)
    }

    @Test
    fun `выполнить по порядку`() {
        val reply = reply("Выполни первую")
        val action = reply.action
        assertTrue(action is VoiceAction.CompleteTask)
        action as VoiceAction.CompleteTask
        assertEquals("1", action.taskId)
    }

    @Test
    fun `выполнить по названию`() {
        val reply = reply("Сделал купить хлеб")
        val action = reply.action as VoiceAction.CompleteTask
        assertEquals("2", action.taskId)
    }

    @Test
    fun `перенести задачу`() {
        val reply = reply("Перенеси позвонить маме на завтра в одиннадцать")
        val action = reply.action
        assertTrue(action is VoiceAction.MoveTask)
        action as VoiceAction.MoveTask
        assertEquals("1", action.taskId)
        assertEquals(at(28, 11), action.dueAt)
    }

    @Test
    fun `убрать в архив`() {
        val reply = reply("Убери купить хлеб в архив")
        val action = reply.action
        assertTrue(action is VoiceAction.ArchiveTask)
        assertEquals("2", (action as VoiceAction.ArchiveTask).taskId)
    }

    @Test
    fun `который час`() {
        val reply = reply("Который час?")
        assertTrue(reply.spoken.contains("10:00"))
        assertTrue(reply.spoken.contains("вторник"))
    }

    @Test
    fun `сколько задач`() {
        val reply = reply("Сколько у меня задач?")
        assertTrue(reply.spoken.contains("3"))
    }

    @Test
    fun `стоп завершает сессию`() {
        val reply = reply("Стоп")
        assertTrue(reply.endSession)
        assertNull(reply.action.let { if (it is VoiceAction.Exit) null else it })
    }

    @Test
    fun `помощь`() {
        val reply = reply("Что ты умеешь?")
        assertTrue(reply.spoken.contains("задачу"))
        assertNull(reply.action)
    }

    @Test
    fun `приветствие не создаёт задачу`() {
        val reply = reply("Привет")
        assertNull(reply.action)
        assertTrue(reply.spoken.contains("Привет") || reply.spoken.contains("Здравствуйте"))
    }

    @Test
    fun `свободная фраза становится задачей`() {
        val reply = reply("купить билеты на поезд")
        val action = reply.action as VoiceAction.AddTask
        assertEquals("Купить билеты на поезд", action.title)
    }

    @Test
    fun `открыть настройки`() {
        val reply = reply("Открой настройки")
        assertTrue(reply.action is VoiceAction.OpenTab)
    }

    @Test
    fun `задачи на завтра`() {
        val tomorrow = callMom.copy(id = "4", title = "Сдать отчёт", dueAt = at(28, 12))
        val reply = reply("Что на завтра?", context(tasks = listOf(tomorrow)))
        assertTrue(reply.spoken.contains("Сдать отчёт"))
    }

    @Test
    fun `просроченные`() {
        val reply = reply("Что просрочено?")
        assertTrue(reply.spoken.contains("Сдать отчёт"))
        assertTrue(reply.taskIds.contains("3"))
    }
}
