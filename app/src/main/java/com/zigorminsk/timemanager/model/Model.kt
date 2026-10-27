package com.zigorminsk.timemanager.model

import java.util.UUID

/** Повтор задачи. */
enum class RepeatRule {
    NONE,
    DAILY,
    WEEKDAYS,
    WEEKLY,
    MONTHLY;

    fun label(): String = when (this) {
        NONE -> "Не повторять"
        DAILY -> "Каждый день"
        WEEKDAYS -> "По будням"
        WEEKLY -> "Каждую неделю"
        MONTHLY -> "Каждый месяц"
    }
}

/** Приоритет (важные задачи показываются поверх экрана блокировки). */
enum class Priority { LOW, NORMAL, HIGH }

/**
 * Место (дом, работа, дача…). Срабатывает при совпадении GPS-радиуса
 * ИЛИ одной из известных Wi-Fi сетей — как только пользователь оказался рядом.
 */
data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = 200,
    val wifiNames: List<String> = emptyList(),
) {
    val hasGeo: Boolean get() = latitude != null && longitude != null
    val hasWifi: Boolean get() = wifiNames.isNotEmpty()
}

/** Задача. */
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val note: String = "",
    /** Время напоминания (epoch millis). null — только по месту. */
    val dueAt: Long? = null,
    /** false — выбрана только дата, время не важно (напоминание в 09:00). */
    val hasTime: Boolean = true,
    /** Место, по прибытии в которое нужно напомнить. */
    val placeId: String? = null,
    val placeName: String = "",
    val repeat: RepeatRule = RepeatRule.NONE,
    val priority: Priority = Priority.NORMAL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val done: Boolean = false,
    val doneAt: Long? = null,
    val archived: Boolean = false,
    val archivedAt: Long? = null,
    /** Если задача отложена — время, раньше которого напоминать не нужно. */
    val snoozedUntil: Long? = null,
    /** Когда последний раз показывали напоминание (для напоминаний по месту). */
    val notifiedAt: Long? = null,
    val postponeCount: Int = 0,
) {
    val isActive: Boolean get() = !done && !archived
    val isPlaceOnly: Boolean get() = placeId != null && dueAt == null
    val isOverdue: Boolean get() = dueAt != null && isActive && dueAt < System.currentTimeMillis()
    val isHighPriority: Boolean get() = priority == Priority.HIGH

    /** Время, когда задача должна «прозвенеть» с учётом отсрочки. */
    fun effectiveAt(): Long? {
        val base = dueAt ?: return snoozedUntil
        val snooze = snoozedUntil ?: return base
        return maxOf(base, snooze)
    }
}

/** Данные приложения целиком (сериализуются в один JSON-файл). */
data class AppData(
    val tasks: List<Task> = emptyList(),
    val places: List<Place> = emptyList(),
) {
    fun activeTasks(): List<Task> = tasks.filter { it.isActive }
    fun archivedTasks(): List<Task> = tasks.filter { it.archived }

    fun tasksForPlace(placeId: String): List<Task> =
        tasks.filter { it.isActive && it.placeId == placeId }

    fun placeById(placeId: String?): Place? = places.firstOrNull { it.id == placeId }
}

/** Настройки приложения. */
data class Settings(
    val themeMode: Int = THEME_SYSTEM,
    val fontScale: Float = 1.0f,
    val speakOnReminder: Boolean = true,
    val speakTasks: Boolean = true,
    val speechRate: Float = 1.0f,
    val autoUpdate: Boolean = true,
    val updateChannel: String = "stable",
    val updateIntervalHours: Int = 12,
    val placeWatch: Boolean = true,
    val vibrate: Boolean = true,
    val silentNoDnd: Boolean = true,
    val fullScreenReminder: Boolean = true,
    val lastUpdateCheck: Long = 0L,
) {
    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}
