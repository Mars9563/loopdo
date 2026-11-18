package app.loopdo.shared.stats

import app.loopdo.shared.habit.DayOutcome
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.activeDayOutcomes
import app.loopdo.shared.habit.habitOutcomes
import app.loopdo.shared.habit.perfectDayOutcomes
import app.loopdo.shared.streak.computeStreak
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.roundToInt

// M8 (spec docs/superpowers/specs/2026-11-18-m8-stats-design.md §3.1): one week's honest numbers, from local data.
// A week is mon–sun in LoopDo dates. Nothing here is stored; the same data always gives the same review.

fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

fun isoWeek(date: LocalDate): Int = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

/** One day of one habit in the review grid. FROZEN = a miss a streak freeze absorbed: still a miss here (owner). */
enum class Cell { DONE, MISSED, FROZEN, NOT_DUE, OPEN, FUTURE }

data class StatTask(val id: String, val title: String, val due: LocalDate?, val completedOn: LocalDate?, val lateSince: LocalDate?)

data class StatHabit(val habit: Habit, val title: String, val sortOrder: Int)

data class WeekInput(val habits: List<StatHabit>, val logs: Map<String, List<HabitLog>>, val tasks: List<StatTask>, val activeDays: Set<LocalDate>)

data class HabitWeek(val id: String, val title: String, val cells: List<Cell>, val done: Int, val due: Int)

data class LateTask(val title: String, val daysLate: Int)

data class WeekReview(
    val start: LocalDate, val number: Int, val done: Int, val total: Int, val percent: Int, val deltaPoints: Int?,
    val perfectDays: Int, val activeDays: Int, val habits: List<HabitWeek>, val best: HabitWeek?,
    val slipped: List<String>, val tasksDone: Int, val late: List<LateTask>,
)

private fun days(start: LocalDate) = (0L until 7L).map { start.plusDays(it) }

private fun habitWeek(h: StatHabit, logs: List<HabitLog>, start: LocalDate, today: LocalDate): HabitWeek {
    val outcomes = habitOutcomes(h.habit, logs, today)
    val byDate = outcomes.toMap()
    val frozen = computeStreak(outcomes).frozenDays
    val cells = days(start).map { d ->
        if (d > today) Cell.FUTURE else when (byDate[d]) {
            DayOutcome.SUCCESS -> Cell.DONE
            DayOutcome.PENDING -> Cell.OPEN
            DayOutcome.MISS -> if (d in frozen) Cell.FROZEN else Cell.MISSED
            DayOutcome.NEUTRAL, null -> Cell.NOT_DUE
        }
    }
    val done = cells.count { it == Cell.DONE }
    return HabitWeek(h.habit.id, h.title, cells, done, done + cells.count { it == Cell.MISSED || it == Cell.FROZEN })
}

private class Counts(val done: Int, val total: Int) {
    val percent get() = if (total == 0) 0 else (100.0 * done / total).roundToInt()
}

private fun counts(habits: List<HabitWeek>, input: WeekInput, start: LocalDate, today: LocalDate): Counts {
    val end = start.plusDays(6)
    val tasks = input.tasks.filter { t -> t.due != null && t.due in start..end && (t.due < today || t.completedOn != null) }
    return Counts(
        habits.sumOf { it.done } + tasks.count { it.completedOn != null && it.completedOn <= it.due!! },
        habits.sumOf { it.due } + tasks.size,
    )
}

/** The week starting [start] (a Monday) as seen on [today]. */
fun weekReview(input: WeekInput, start: LocalDate, today: LocalDate): WeekReview {
    val end = start.plusDays(6)
    val habits = input.habits.sortedBy { it.sortOrder }
        .map { habitWeek(it, input.logs[it.habit.id].orEmpty(), start, today) }
        .filter { h -> h.cells.any { it != Cell.NOT_DUE && it != Cell.FUTURE } }
    val c = counts(habits, input, start, today)
    val prevStart = start.minusDays(7)
    val prev = counts(input.habits.map { habitWeek(it, input.logs[it.habit.id].orEmpty(), prevStart, today) }, input, prevStart, today)
    val last = minOf(today, end)
    val perfect = if (today < start) 0 else perfectDayOutcomes(input.habits.map { it.habit }, input.logs, start, last).count { it.second == DayOutcome.SUCCESS }
    val active = if (today < start) 0 else activeDayOutcomes(input.activeDays, start, last).count { it.second == DayOutcome.SUCCESS }
    val order = input.habits.associate { it.habit.id to it.sortOrder }
    val best = habits.filter { it.due > 0 }
        .sortedWith(compareByDescending<HabitWeek> { it.done.toDouble() / it.due }.thenByDescending { it.due }.thenBy { order[it.id] })
        .firstOrNull()
    val asOf = if (today <= end) today else end.plusDays(1)
    val late = input.tasks.filter { it.completedOn == null && it.due != null && it.due < asOf && (it.due >= start || it.lateSince != null) }
        .sortedBy { it.due }
        .map { LateTask(it.title, ChronoUnit.DAYS.between(it.due, asOf).toInt()) }
    val slipped = habits.filter { it.due > 0 && it.done < it.due }
        .sortedWith(compareByDescending<HabitWeek> { it.due - it.done }.thenBy { order[it.id] })
        .map { "${it.title} ${it.done}/${it.due}" } + late.map { "${it.title} late" }
    return WeekReview(
        start, isoWeek(start), c.done, c.total, c.percent, if (prev.total == 0) null else c.percent - prev.percent,
        perfect, active, habits, best, slipped.take(2),
        input.tasks.count { it.completedOn != null && it.completedOn in start..end }, late,
    )
}
