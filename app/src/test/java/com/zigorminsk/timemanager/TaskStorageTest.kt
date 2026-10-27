package com.zigorminsk.timemanager

import com.zigorminsk.timemanager.data.TaskRepository
import com.zigorminsk.timemanager.model.AppData
import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.model.Priority
import com.zigorminsk.timemanager.model.RepeatRule
import com.zigorminsk.timemanager.model.Task
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверяет путь сохранения задачи: модель → JSON → модель. */
class TaskStorageTest {

    private val place = Place(
        id = "p1",
        name = "Дом",
        latitude = 53.9,
        longitude = 27.5,
        radiusMeters = 150,
        wifiNames = listOf("Home_5G", "HOME"),
    )

    private val task = Task(
        id = "t1",
        title = "Взять дрель",
        note = "Та самая, из кладовки",
        dueAt = 1_800_000_000_000L,
        hasTime = false,
        placeId = "p1",
        placeName = "Дом",
        repeat = RepeatRule.WEEKDAYS,
        priority = Priority.HIGH,
        done = false,
        archived = false,
        snoozedUntil = null,
        postponeCount = 2,
    )

    @Test
    fun `задача и место переживают сохранение и загрузку`() {
        val json = TaskRepository.toJson(AppData(tasks = listOf(task), places = listOf(place)))
        val restored = TaskRepository.fromJson(JSONObject(json.toString()))

        assertEquals(1, restored.tasks.size)
        val restoredTask = restored.tasks.first()
        assertEquals(task.id, restoredTask.id)
        assertEquals(task.title, restoredTask.title)
        assertEquals(task.note, restoredTask.note)
        assertEquals(task.dueAt, restoredTask.dueAt)
        assertEquals(false, restoredTask.hasTime)
        assertEquals("p1", restoredTask.placeId)
        assertEquals(RepeatRule.WEEKDAYS, restoredTask.repeat)
        assertEquals(Priority.HIGH, restoredTask.priority)
        assertEquals(2, restoredTask.postponeCount)
        assertTrue(restoredTask.isActive)

        val restoredPlace = restored.places.first()
        assertEquals("Дом", restoredPlace.name)
        assertEquals(150, restoredPlace.radiusMeters)
        assertEquals(listOf("Home_5G", "HOME"), restoredPlace.wifiNames)
        assertEquals(53.9, restoredPlace.latitude!!, 0.000001)
    }

    @Test
    fun `задача без времени и места сохраняется корректно`() {
        val simple = Task(id = "t2", title = "Просто заметка")
        val restored = TaskRepository.fromJson(
            JSONObject(TaskRepository.toJson(AppData(tasks = listOf(simple))).toString()),
        ).tasks.first()

        assertEquals("t2", restored.id)
        assertNull(restored.dueAt)
        assertNull(restored.placeId)
        assertTrue(restored.hasTime)
        assertEquals(RepeatRule.NONE, restored.repeat)
    }

    @Test
    fun `выполненная задача попадает в архив`() {
        val done = task.copy(done = true, archived = true, doneAt = 1_900_000_000_000L)
        val restored = TaskRepository.fromJson(
            JSONObject(TaskRepository.toJson(AppData(tasks = listOf(done))).toString()),
        ).tasks.first()

        assertTrue(restored.done)
        assertTrue(restored.archived)
        assertEquals(false, restored.isActive)
        assertEquals(1_900_000_000_000L, restored.doneAt)
    }
}
