package com.zigorminsk.timemanager.util

import com.zigorminsk.timemanager.model.Task
import kotlin.math.abs

/** Пересечения задач по времени. */
object Conflicts {

    /** Две задачи «накладываются», если их время отличается меньше чем на 30 минут. */
    const val WINDOW_MS = 30 * 60_000L

    fun find(tasks: List<Task>, dueAt: Long?, exceptId: String? = null): List<Task> {
        if (dueAt == null) return emptyList()
        return tasks
            .filter { it.isActive && it.dueAt != null && it.id != exceptId }
            .filter { abs(it.dueAt!! - dueAt) < WINDOW_MS }
            .sortedBy { it.dueAt }
    }

    /** Задачи, у которых есть «сосед» по времени. */
    fun overlapping(tasks: List<Task>): Set<String> {
        val active = tasks.filter { it.isActive && it.dueAt != null }
        val result = mutableSetOf<String>()
        for (i in active.indices) {
            for (j in i + 1 until active.size) {
                val a = active[i]
                val b = active[j]
                if (abs(a.dueAt!! - b.dueAt!!) < WINDOW_MS) {
                    result += a.id
                    result += b.id
                }
            }
        }
        return result
    }
}
