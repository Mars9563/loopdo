package app.loopdo.client.stats

import app.loopdo.shared.habit.isDue
import app.loopdo.shared.stats.Completion
import app.loopdo.shared.stats.Focus
import app.loopdo.shared.stats.FocusKind
import app.loopdo.shared.stats.MoodRow
import app.loopdo.shared.stats.PixelMode
import app.loopdo.shared.stats.WeekReview
import app.loopdo.shared.stats.completion
import app.loopdo.shared.stats.defaultGoal
import app.loopdo.shared.stats.focusLine
import app.loopdo.shared.stats.focusResult
import app.loopdo.shared.stats.focusWindowOpen
import app.loopdo.shared.stats.kept
import app.loopdo.shared.stats.moodVsHabits
import app.loopdo.shared.stats.reviewWeekFor
import app.loopdo.shared.stats.taskDeadline
import app.loopdo.shared.stats.weekReview
import app.loopdo.shared.stats.weekStart
import app.loopdo.shared.stats.yearPixels
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// M8: the stats tab (locked screen 4) and the weekly review (locked weekly-review-v1 A/B) as plain data, so every
// rule on them is tested on the JVM. Built from StatsRows and the trusted LoopDo date.

class StatsUi(
    val today: LocalDate, val year: Int, val mode: PixelMode, val pixels: Array<IntArray>,
    val week: WeekReview, val moodRows: List<MoodRow>?, val completion: List<Completion>, val last30: Boolean,
)

/** One row of the focus picker (mock B). */
data class PickItem(val kind: FocusKind, val itemId: String, val title: String, val goal: Int?, val deadline: LocalDate?, val note: String)

data class ReviewUi(
    val review: WeekReview,
    /** Today is the week's Sunday ("today is its last day"). */
    val isLastDay: Boolean,
    /** LAST WEEK YOU SAID: the promises made for this week, checked. */
    val lastWeekSaid: List<String>,
    val kept: Int,
    /** Items promised for this week: marked `›` in the grid. */
    val focusIds: Set<String>,
    val nextWeek: LocalDate,
    val next: List<Focus>,
    val canEdit: Boolean,
    /** "pick 1–2" while editable, "opens sun" before the window, "closed" after it. */
    val nextNote: String,
    val canGoForward: Boolean,
    val picker: List<PickItem>,
)

fun buildStats(rows: StatsRows, today: LocalDate, year: Int, mode: PixelMode, last30: Boolean) = StatsUi(
    today, year, mode, yearPixels(year, mode, rows.moods, rows.input, today),
    weekReview(rows.input, weekStart(today), today),
    moodVsHabits(rows.moods, rows.journalDays, rows.input, today),
    completion(rows.input, today, last30), last30,
)

private fun LocalDate.dow() = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()

/** The review of [weekStart] (null = the one `[review >]` opens today, spec §2.2). */
fun buildReview(rows: StatsRows, today: LocalDate, weekStart: LocalDate?): ReviewUi {
    val start = weekStart ?: reviewWeekFor(today)
    val promises = rows.focus.filter { it.weekStart == start }
    val results = promises.map { it to focusResult(it, rows.input, today) }
    val next = start.plusDays(7)
    val canEdit = focusWindowOpen(next, today)
    val picker = rows.input.habits.filter { !it.hidden }.sortedBy { it.sortOrder }
        .filter { h -> (0L until 7L).map { next.plusDays(it) }.any { d -> (h.habit.archivedOn == null || d < h.habit.archivedOn) && isDue(h.habit.schedules, d) } }
        .map { h -> defaultGoal(h.habit, next).let { g -> PickItem(FocusKind.HABIT, h.habit.id, h.title, g, null, "$g due") } } +
        rows.input.tasks.filter { it.completedOn == null }.sortedWith(compareBy(nullsLast()) { it.due }).map { t ->
            val due = t.due
            val note = when {
                due == null -> "no date"
                due < today -> "late ${java.time.temporal.ChronoUnit.DAYS.between(due, today)}d"
                else -> "due ${due.dow()}"
            }
            PickItem(FocusKind.TASK, t.id, t.title, null, taskDeadline(t.due, next), note)
        }
    return ReviewUi(
        review = weekReview(rows.input, start, today),
        isLastDay = today == start.plusDays(6),
        lastWeekSaid = results.map { (f, r) -> focusLine(f, r, today) },
        kept = results.count { it.second.kept() },
        focusIds = promises.map { it.itemId }.toSet(),
        nextWeek = next,
        next = rows.focus.filter { it.weekStart == next },
        canEdit = canEdit,
        nextNote = when { canEdit -> "pick 1–2"; today < next.minusDays(1) -> "opens sun"; else -> "closed" },
        canGoForward = start < reviewWeekFor(today),
        picker = picker,
    )
}

/** "16–22 nov", or "30 nov–6 dec" across months. */
fun weekRange(start: LocalDate): String {
    val end = start.plusDays(6)
    val f = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    return (if (start.month == end.month) "${start.dayOfMonth}–${end.format(f)}" else "${start.format(f)}–${end.format(f)}").lowercase()
}

/** Opening the review on a Sunday silences that Sunday's 20:00 ping (spec §4.3); any other day doesn't. */
fun sundayToSilence(today: LocalDate): LocalDate? = today.takeIf { it.dayOfWeek == DayOfWeek.SUNDAY }
