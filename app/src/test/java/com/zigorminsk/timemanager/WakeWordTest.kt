package com.zigorminsk.timemanager

import com.zigorminsk.timemanager.voice.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {

    @Test
    fun `фраза в начале команды`() {
        val match = WakeWord.split("Слушай, Тайм, что на сегодня?")
        assertTrue(match.found)
        assertEquals("что на сегодня", match.command)
    }

    @Test
    fun `фраза без команды`() {
        val match = WakeWord.split("Слушай, Тайм!")
        assertTrue(match.found)
        assertTrue(match.command.isBlank())
    }

    @Test
    fun `варианты распознавания`() {
        listOf(
            "слушай таймер",
            "эй тайм",
            "окей тайм",
            "слyшай тайм",
            "слушай, тай м",
            "Слушай тайм напомни купить хлеб",
        ).forEach { phrase ->
            assertTrue("должно распознаться: $phrase", WakeWord.contains(phrase))
        }
    }

    @Test
    fun `обычная речь не срабатывает`() {
        listOf(
            "покажи задачи",
            "слушай музыку",
            "поставь таймер на десять минут",
            "доброе утро",
        ).forEach { phrase ->
            assertFalse("не должно срабатывать: $phrase", WakeWord.contains(phrase))
        }
    }

    @Test
    fun `команда после обращения`() {
        val match = WakeWord.split("Эй, Тайм, напомни позвонить маме завтра в три")
        assertTrue(match.found)
        assertEquals("напомни позвонить маме завтра в три", match.command)
    }

    @Test
    fun `своя фраза`() {
        assertTrue(WakeWord.contains("слушай помощник, что дальше", "слушай помощник"))
        val match = WakeWord.split("Слушай, помощник, отложи на десять минут", "слушай помощник")
        assertTrue(match.found)
        assertEquals("отложи на десять минут", match.command)
    }

    @Test
    fun `стоп-слова`() {
        assertTrue(WakeWord.isStop("стоп"))
        assertTrue(WakeWord.isStop("Хватит."))
        assertTrue(WakeWord.isStop("пока"))
        assertTrue(WakeWord.isStop("замолчи пожалуйста"))
        assertFalse(WakeWord.isStop("покажи задачи"))
        assertFalse(WakeWord.isStop("стоп-кран купить"))
    }
}
