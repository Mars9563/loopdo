package app.loopdo.shared.stats

import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.isDue
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// M8 (spec §2.3, §3.2–3.3): the weekly focus — 1–2 promises for a week, set on its Sunday-before or Monday,
// checked from the data afterwards. The server uses [focusWindowOpen] and [MAX_FOCUS] too.

enum class FocusKind { HABIT, TASK }

/** [goal]: a habit's "N times this week"; [deadline]: a task's day, saved when picked. [title] is a copy. */
data class Focus(val id: String, val weekStart: LocalDate, val kind: FocusKind, val itemId: String, val title: String, val goal: Int?, val deadline: LocalDate?)

const val MAX_FOCUS = 2

/** The focus of the week starting [weekStart] can change only on its Sunday-before or its Monday (LoopDo dates). */
fun focusWindowOpen(weekStart: LocalDate, today: LocalDate): Boolean = today == weekStart.minusDays(1) || today == weekStart

/** The week whose focus can be set on [today]: Sunday → next week; Monday → this week; otherwise none. */
fun focusWeekFor(today: LocalDate): LocalDate? = listOf(today.plusDays(1), today).firstOrNull { it == weekStart(it) && focusWindowOpen(it, today) }

/** The week `[review >]` opens on [today]: Monday → the week that just ended (catch-up); otherwise the current week. */
fun reviewWeekFor(today: LocalDate): LocalDate = weekStart(if (today == weekStart(today)) today.minusDays(1) else today)

/** How many days [habit] is due in the week, 1..7 (the picker's default goal). */
fun defaultGoal(habit: Habit, weekStart: LocalDate): Int =
    (0L until 7L).map { weekStart.plusDays(it) }.count { d -> (habit.archivedOn == null || d < habit.archivedOn) && isDue(habit.schedules, d) }.coerceIn(1, 7)

/** A task's promise: its due day if inside the week, else the week's Sunday (late, undated or due later). */
fun taskDeadline(due: LocalDate?, weekStart: LocalDate): LocalDate {
    val sunday = weekStart.plusDays(6)
    return if (due != null && due in weekStart..sunday) due else sunday
}

sealed interface FocusResult {
    /** [left] = the week's due days still to come (today included while open): could the goal still be met? */
    data class Habit(val done: Int, val goal: Int, val left: Int = 0) : FocusResult
    data class Task(val completedOn: LocalDate?, val deadline: LocalDate) : FocusResult
}

fun FocusResult.kept(): Boolean = when (this) {
    is FocusResult.Habit -> done >= goal
    is FocusResult.Task -> completedOn != null && completedOn <= deadline
}

fun focusResult(f: Focus, input: WeekInput, today: LocalDate): FocusResult = when (f.kind) {
    FocusKind.HABIT -> {
        val h = input.habits.firstOrNull { it.habit.id == f.itemId }
        val week = h?.let { habitWeek(it, input.logs[it.habit.id].orEmpty(), f.weekStart, today) }
        val left = if (h == null) 0 else (0L until 7L).map { f.weekStart.plusDays(it) }.count { d ->
            (d > today || week!!.cells[(d.toEpochDay() - f.weekStart.toEpochDay()).toInt()] == Cell.OPEN) &&
                (h.habit.archivedOn == null || d < h.habit.archivedOn) && isDue(h.habit.schedules, d)
        }
        FocusResult.Habit(week?.done ?: 0, f.goal ?: 1, left)
    }
    FocusKind.TASK -> FocusResult.Task(
        input.tasks.firstOrNull { it.id == f.itemId }?.completedOn?.takeIf { it <= today },
        f.deadline ?: taskDeadline(null, f.weekStart),
    )
}

private fun LocalDate.dow() = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()

/** "gym 4×  →  3/4 · missed by 1", "pay rent  →  done tue ✓"; while the week runs, "1/4 so far" / "due wed". */
fun focusLine(f: Focus, r: FocusResult, today: LocalDate): String {
    val weekOver = today > f.weekStart.plusDays(6)
    return when (r) {
        is FocusResult.Habit -> "${f.title} ${r.goal}×  →  ${r.done}/${r.goal}" + when {
            r.kept() -> " ✓"
            !weekOver && r.done + r.left >= r.goal -> " so far" // the review on a sunday evening says it plainly (locked mock A)
            else -> " · missed by ${r.goal - r.done}"
        }
        is FocusResult.Task -> "${f.title}  →  " + when {
            r.completedOn == null && today <= r.deadline -> "due ${r.deadline.dow()}"
            r.completedOn == null -> "not done"
            r.kept() -> "done ${r.completedOn.dow()} ✓"
            else -> "done ${r.completedOn.dow()} · late"
        }
    }
}
