package app.loopdo.shared.habit

import java.time.DayOfWeek
import java.time.LocalDate

/** PAUSED: nothing is due (a planned break, set from tomorrow at the earliest; M5). */
enum class Recurrence { DAILY, WEEKDAYS, PAUSED }

/**
 * One version of a habit's schedule, in force from [validFrom] until the next version.
 * Editing a schedule adds a version starting tomorrow, so past days keep being
 * judged by the schedule they actually had.
 */
data class HabitSchedule(
    val validFrom: LocalDate,
    val recurrence: Recurrence,
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** The count target from [validFrom] on; null = the habit's own target (versions before M5). */
    val target: Int? = null,
) {
    init {
        when (recurrence) {
            Recurrence.DAILY, Recurrence.PAUSED -> require(weekdays.isEmpty()) { "a daily or paused schedule has no weekdays" }
            Recurrence.WEEKDAYS -> require(weekdays.isNotEmpty()) { "pick at least one weekday" }
        }
        require(target == null || target >= 1) { "the target must be at least 1" }
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
        Recurrence.PAUSED -> false
    }
}

/**
 * The two versions a pause adds: PAUSED from [from], then [current] again from the day after [to].
 * The caller makes [from] tomorrow at the earliest, so a pause can never hide a day already missed.
 */
fun pauseVersions(current: HabitSchedule, from: LocalDate, to: LocalDate): List<HabitSchedule> {
    require(to >= from) { "a pause ends on or after the day it starts" }
    return listOf(HabitSchedule(from, Recurrence.PAUSED), current.copy(validFrom = to.plusDays(1)))
}

/** Schedule edits take effect from the next LoopDo day (spec 6.2). */
fun nextVersionValidFrom(today: LocalDate): LocalDate = today.plusDays(1)
