package com.zigorminsk.timemanager.data

import android.content.Context
import android.content.SharedPreferences
import com.zigorminsk.timemanager.model.AppData
import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.model.Priority
import com.zigorminsk.timemanager.model.RepeatRule
import com.zigorminsk.timemanager.model.Settings
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.util.RuTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Хранилище задач и мест. Один JSON-файл во внутренней памяти приложения,
 * состояние в памяти + StateFlow для Compose.
 */
class TaskRepository(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val file = File(appContext.filesDir, "timemanager.json")

    private val _state = MutableStateFlow(loadFromDisk())
    val state: StateFlow<AppData> = _state.asStateFlow()

    val data: AppData get() = _state.value

    /** Вызывается после каждого изменения — чтобы пересобрать будильники и гео-слежение. */
    var onChange: ((AppData) -> Unit)? = null

    // ---------------------------------------------------------------- задачи

    fun addTask(task: Task) = mutate { it.copy(tasks = it.tasks + task) }

    fun updateTask(task: Task) = mutate { data ->
        data.copy(tasks = data.tasks.map { if (it.id == task.id) task.copy(updatedAt = System.currentTimeMillis()) else it })
    }

    fun deleteTask(id: String) = mutate { data ->
        data.copy(tasks = data.tasks.filterNot { it.id == id })
    }

    fun taskById(id: String): Task? = data.tasks.firstOrNull { it.id == id }

    /** Отметить выполненной: задача уходит в архив, повторяющаяся — создаёт следующую. */
    fun completeTask(id: String) {
        val now = System.currentTimeMillis()
        var nextTask: Task? = null
        mutate { data ->
            val task = data.tasks.firstOrNull { it.id == id } ?: return@mutate data
            if (task.done) return@mutate data
            val done = task.copy(
                done = true,
                doneAt = now,
                archived = true,
                archivedAt = now,
                snoozedUntil = null,
                updatedAt = now,
            )
            val tasks = data.tasks.map { if (it.id == id) done else it }
            nextTask = if (task.repeat != RepeatRule.NONE) {
                RuTime.nextRepeatMillis(task)?.let { next ->
                    task.copy(
                        id = UUID.randomUUID().toString(),
                        dueAt = next,
                        done = false,
                        doneAt = null,
                        archived = false,
                        archivedAt = null,
                        snoozedUntil = null,
                        notifiedAt = null,
                        postponeCount = 0,
                        createdAt = now,
                        updatedAt = now,
                    )
                }
            } else {
                null
            }
            if (nextTask != null) data.copy(tasks = tasks + nextTask!!) else data.copy(tasks = tasks)
        }
    }

    /** Вернуть из архива в работу. */
    fun restoreTask(id: String) = mutate { data ->
        data.copy(tasks = data.tasks.map {
            if (it.id == id) it.copy(done = false, doneAt = null, archived = false, archivedAt = null, snoozedUntil = null) else it
        })
    }

    fun archiveTask(id: String) = mutate { data ->
        data.copy(tasks = data.tasks.map {
            if (it.id == id) it.copy(archived = true, archivedAt = System.currentTimeMillis(), snoozedUntil = null) else it
        })
    }

    /** Отложить задачу на N минут (кнопка «Отложить»). */
    fun postponeTask(id: String, minutes: Int) = postponeUntil(id, System.currentTimeMillis() + minutes * 60_000L)

    fun postponeUntil(id: String, untilMillis: Long) = mutate { data ->
        data.copy(tasks = data.tasks.map {
            if (it.id == id) it.copy(
                snoozedUntil = untilMillis,
                postponeCount = it.postponeCount + 1,
                updatedAt = System.currentTimeMillis(),
            ) else it
        })
    }

    /** Перенести задачу на другую дату/время. */
    fun moveTask(id: String, dueAt: Long?, hasTime: Boolean) = mutate { data ->
        data.copy(tasks = data.tasks.map {
            if (it.id == id) it.copy(
                dueAt = dueAt,
                hasTime = hasTime,
                snoozedUntil = null,
                notifiedAt = null,
                updatedAt = System.currentTimeMillis(),
            ) else it
        })
    }

    fun setPlace(id: String, placeId: String?, placeName: String) = mutate { data ->
        data.copy(tasks = data.tasks.map {
            if (it.id == id) it.copy(placeId = placeId, placeName = placeName) else it
        })
    }

    fun markNotified(id: String, at: Long = System.currentTimeMillis()) = mutate { data ->
        data.copy(tasks = data.tasks.map {
            if (it.id == id) it.copy(notifiedAt = at) else it
        })
    }

    fun clearArchive() = mutate { data -> data.copy(tasks = data.tasks.filterNot { it.archived }) }

    /** Задачи, которые должны напомнить о себе в указанный момент (для восстановления будильников). */
    fun upcomingTasks(from: Long = System.currentTimeMillis()): List<Task> =
        data.activeTasks().filter { it.effectiveAt()?.let { t -> t > from } == true }

    // ---------------------------------------------------------------- места

    fun addPlace(place: Place) = mutate { it.copy(places = it.places + place) }

    fun updatePlace(place: Place) = mutate { data ->
        val renamedTasks = data.tasks.map {
            if (it.placeId == place.id) it.copy(placeName = place.name) else it
        }
        data.copy(
            places = data.places.map { if (it.id == place.id) place else it },
            tasks = renamedTasks,
        )
    }

    fun deletePlace(id: String) = mutate { data ->
        data.copy(
            places = data.places.filterNot { it.id == id },
            tasks = data.tasks.map {
                if (it.placeId == id) it.copy(placeId = null, placeName = "") else it
            },
        )
    }

    fun placeById(id: String?): Place? = data.places.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- прочее

    /** Экспорт всех данных в JSON-строку. */
    fun exportJson(): String = toJson(data).toString(2)

    /** Импорт (используется кнопкой «Экспорт/Импорт» и тестами). */
    fun importJson(json: String) {
        val parsed = fromJson(JSONObject(json))
        mutate { parsed }
    }

    // ---------------------------------------------------------------- внутреннее

    private fun mutate(transform: (AppData) -> AppData) {
        synchronized(lock) {
            val before = _state.value
            val after = transform(before)
            if (after == before) return
            _state.value = after
            persist(after)
            onChange?.invoke(after)
        }
    }

    private fun persist(data: AppData) {
        runCatching {
            val tmp = File(appContext.filesDir, "timemanager.json.tmp")
            tmp.writeText(toJson(data).toString())
            // rename(2) атомарно заменяет файл — данные не потеряются при сбое
            if (!tmp.renameTo(file)) {
                file.writeText(toJson(data).toString())
                tmp.delete()
            }
        }
    }

    private fun loadFromDisk(): AppData = runCatching {
        if (!file.exists()) return AppData()
        fromJson(JSONObject(file.readText()))
    }.getOrElse { AppData() }

    companion object {

        fun toJson(data: AppData): JSONObject = JSONObject().apply {
            put("version", 1)
            put("tasks", JSONArray().apply { data.tasks.forEach { put(taskToJson(it)) } })
            put("places", JSONArray().apply { data.places.forEach { put(placeToJson(it)) } })
        }

        fun fromJson(obj: JSONObject): AppData {
            val tasks = mutableListOf<Task>()
            val places = mutableListOf<Place>()
            obj.optJSONArray("places")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { places += placeFromJson(it) }
            }
            obj.optJSONArray("tasks")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { tasks += taskFromJson(it) }
            }
            return AppData(tasks = tasks, places = places)
        }

        private fun taskToJson(t: Task): JSONObject = JSONObject().apply {
            put("id", t.id)
            put("title", t.title)
            put("note", t.note)
            put("dueAt", t.dueAt ?: JSONObject.NULL)
            put("hasTime", t.hasTime)
            put("placeId", t.placeId ?: JSONObject.NULL)
            put("placeName", t.placeName)
            put("repeat", t.repeat.name)
            put("priority", t.priority.name)
            put("createdAt", t.createdAt)
            put("updatedAt", t.updatedAt)
            put("done", t.done)
            put("doneAt", t.doneAt ?: JSONObject.NULL)
            put("archived", t.archived)
            put("archivedAt", t.archivedAt ?: JSONObject.NULL)
            put("snoozedUntil", t.snoozedUntil ?: JSONObject.NULL)
            put("notifiedAt", t.notifiedAt ?: JSONObject.NULL)
            put("postponeCount", t.postponeCount)
        }

        private fun taskFromJson(o: JSONObject): Task = Task(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", ""),
            note = o.optString("note", ""),
            dueAt = o.optLongOrNull("dueAt"),
            hasTime = o.optBoolean("hasTime", true),
            placeId = o.optStringOrNull("placeId"),
            placeName = o.optString("placeName", ""),
            repeat = runCatching { RepeatRule.valueOf(o.optString("repeat")) }.getOrDefault(RepeatRule.NONE),
            priority = runCatching { Priority.valueOf(o.optString("priority")) }.getOrDefault(Priority.NORMAL),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            done = o.optBoolean("done", false),
            doneAt = o.optLongOrNull("doneAt"),
            archived = o.optBoolean("archived", false),
            archivedAt = o.optLongOrNull("archivedAt"),
            snoozedUntil = o.optLongOrNull("snoozedUntil"),
            notifiedAt = o.optLongOrNull("notifiedAt"),
            postponeCount = o.optInt("postponeCount", 0),
        )

        private fun placeToJson(p: Place): JSONObject = JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("latitude", p.latitude ?: JSONObject.NULL)
            put("longitude", p.longitude ?: JSONObject.NULL)
            put("radiusMeters", p.radiusMeters)
            put("wifiNames", JSONArray().apply { p.wifiNames.forEach { put(it) } })
        }

        private fun placeFromJson(o: JSONObject): Place {
            val wifi = mutableListOf<String>()
            o.optJSONArray("wifiNames")?.let { arr ->
                for (i in 0 until arr.length()) wifi += arr.optString(i)
            }
            return Place(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", ""),
                latitude = o.optDoubleOrNull("latitude"),
                longitude = o.optDoubleOrNull("longitude"),
                radiusMeters = o.optInt("radiusMeters", 200),
                wifiNames = wifi,
            )
        }

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (!has(key) || isNull(key)) null else optLong(key)

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (!has(key) || isNull(key)) null else optDouble(key)

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }
    }
}

/** Настройки в SharedPreferences + StateFlow для Compose. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val value: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit()
            .putInt("themeMode", next.themeMode)
            .putFloat("fontScale", next.fontScale)
            .putBoolean("speakOnReminder", next.speakOnReminder)
            .putBoolean("speakTasks", next.speakTasks)
            .putFloat("speechRate", next.speechRate)
            .putBoolean("autoUpdate", next.autoUpdate)
            .putString("updateChannel", next.updateChannel)
            .putInt("updateIntervalHours", next.updateIntervalHours)
            .putBoolean("placeWatch", next.placeWatch)
            .putBoolean("vibrate", next.vibrate)
            .putBoolean("silentNoDnd", next.silentNoDnd)
            .putBoolean("fullScreenReminder", next.fullScreenReminder)
            .putLong("lastUpdateCheck", next.lastUpdateCheck)
            .apply()
    }

    private fun read(): Settings = Settings(
        themeMode = prefs.getInt("themeMode", Settings.THEME_SYSTEM),
        fontScale = prefs.getFloat("fontScale", 1.0f),
        speakOnReminder = prefs.getBoolean("speakOnReminder", true),
        speakTasks = prefs.getBoolean("speakTasks", true),
        speechRate = prefs.getFloat("speechRate", 1.0f),
        autoUpdate = prefs.getBoolean("autoUpdate", true),
        updateChannel = prefs.getString("updateChannel", "stable") ?: "stable",
        updateIntervalHours = prefs.getInt("updateIntervalHours", 12),
        placeWatch = prefs.getBoolean("placeWatch", true),
        vibrate = prefs.getBoolean("vibrate", true),
        silentNoDnd = prefs.getBoolean("silentNoDnd", true),
        fullScreenReminder = prefs.getBoolean("fullScreenReminder", true),
        lastUpdateCheck = prefs.getLong("lastUpdateCheck", 0L),
    )
}
