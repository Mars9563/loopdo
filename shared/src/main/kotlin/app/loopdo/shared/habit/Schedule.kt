package app.loopdo.shared.habit

import java.time.DayOfWeek
import java.time.LocalDate

enum class Recurrence { DAILY, WEEKDAYS }

/**
 * One version of a habit's schedule, in force from [validFrom] until the next version.
 * Editing a schedule adds a version starting tomorrow, so past days keep being
 * judged by the schedule they actually had.
 */
data class HabitSchedule(
    val validFrom: LocalDate,
    val recurrence: Recurrence,
    val weekdays: Set<DayOfWeek> = emptySet(),
) {
    init {
        when (recurrence) {
            Recurrence.DAILY -> require(weekdays.isEmpty()) { "a daily schedule has no weekdays" }
            Recurrence.WEEKDAYS -> require(weekdays.isNotEmpty()) { "pick at least one weekday" }
        }
    }
}

/** The schedule version in force on [date], or null before the habit started. */
fun scheduleOn(schedules: List<HabitSchedule>, date: LocalDate): HabitSchedule? =
    schedules.filter { it.validFrom <= date }.maxByOrNull { it.validFrom }

fun isDue(schedules: List<HabitSchedule>, date: LocalDate): Boolean {
    val s = scheduleOn(schedules, date) ?: return false
    return when (s.recurrence) {
        Recurrence.DAILY -> true
        Recurrence.WEEKDAYS -> date.dayOfWeek in s.weekdays
    }
}

/** Schedule edits take effect from the next LoopDo day (spec 6.2). */
fun nextVersionValidFrom(today: LocalDate): LocalDate = today.plusDays(1)
