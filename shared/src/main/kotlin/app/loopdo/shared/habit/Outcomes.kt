package app.loopdo.shared.habit

import java.time.LocalDate

/** How one LoopDo day went for one streak. */
enum class DayOutcome {
    SUCCESS,
    MISS,
    /** Nothing was asked of that day (e.g. not a gym day). Never breaks a streak. */
    NEUTRAL,
    /** Today, not done yet. Never breaks a streak early. */
    PENDING,
}

enum class HabitKind { YES_NO, COUNT }

/** [target] is 1 for yes/no habits. [archivedOn] is the first day the habit no longer runs. */
data class Habit(
    val id: String,
    val kind: HabitKind,
    val target: Int,
    val schedules: List<HabitSchedule>,
    val archivedOn: LocalDate? = null,
)

data class HabitLog(val date: LocalDate, val count: Int, val verified: Boolean = true)

typealias DayRecord = Pair<LocalDate, DayOutcome>

private fun days(start: LocalDate, endInclusive: LocalDate): Sequence<LocalDate> =
    generateSequence(start) { it.plusDays(1) }.takeWhile { it <= endInclusive }

private fun Habit.activeOn(date: LocalDate) = archivedOn == null || date < archivedOn

private fun Habit.doneOn(date: LocalDate, countsByDate: Map<LocalDate, Int>) =
    (countsByDate[date] ?: 0) >= target

private fun countsByDate(logs: List<HabitLog>): Map<LocalDate, Int> =
    logs.groupBy { it.date }.mapValues { (_, l) -> l.maxOf { it.count } }

/** One record per day from the habit's first schedule to [today] (or until it was archived). */
fun habitOutcomes(h: Habit, logs: List<HabitLog>, today: LocalDate): List<DayRecord> {
    val start = h.schedules.minOfOrNull { it.validFrom } ?: return emptyList()
    val counts = countsByDate(logs)
    return days(start, today).takeWhile { h.activeOn(it) }.map { date ->
        date to when {
            !isDue(h.schedules, date) -> DayOutcome.NEUTRAL
            h.doneOn(date, counts) -> DayOutcome.SUCCESS
            date == today -> DayOutcome.PENDING
            else -> DayOutcome.MISS
        }
    }.toList()
}

/** A day succeeds if it is in [presentDays]; today without it is still pending. */
private fun presenceOutcomes(presentDays: Set<LocalDate>, start: LocalDate, today: LocalDate): List<DayRecord> =
    days(start, today).map { date ->
        date to when {
            date in presentDays -> DayOutcome.SUCCESS
            date == today -> DayOutcome.PENDING
            else -> DayOutcome.MISS
        }
    }.toList()

/** [entryDays]: days whose journal entry has any text or at least one photo. */
fun journalOutcomes(entryDays: Set<LocalDate>, start: LocalDate, today: LocalDate): List<DayRecord> =
    presenceOutcomes(entryDays, start, today)

/** [activeDays]: days with any habit tick, task completion or journal entry. */
fun activeDayOutcomes(activeDays: Set<LocalDate>, start: LocalDate, today: LocalDate): List<DayRecord> =
    presenceOutcomes(activeDays, start, today)

/** A perfect day: every habit due that day was done. Days with nothing due are neutral. */
fun perfectDayOutcomes(
    habits: List<Habit>,
    logs: Map<String, List<HabitLog>>,
    start: LocalDate,
    today: LocalDate,
): List<DayRecord> {
    val counts = habits.associate { it.id to countsByDate(logs[it.id].orEmpty()) }
    return days(start, today).map { date ->
        val due = habits.filter { it.activeOn(date) && isDue(it.schedules, date) }
        val allDone = due.all { it.doneOn(date, counts.getValue(it.id)) }
        date to when {
            due.isEmpty() -> DayOutcome.NEUTRAL
            allDone -> DayOutcome.SUCCESS
            date == today -> DayOutcome.PENDING
            else -> DayOutcome.MISS
        }
    }.toList()
}
