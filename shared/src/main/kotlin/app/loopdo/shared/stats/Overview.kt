package app.loopdo.shared.stats

import app.loopdo.shared.habit.DayOutcome
import app.loopdo.shared.habit.habitOutcomes
import app.loopdo.shared.habit.perfectDayOutcomes
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

// M8 (spec §2.1, locked stats screen): Year in Pixels, mood vs habits, completion. Mood is a number per day;
// no journal text is ever read here (owner).

enum class PixelMode { MOOD, PERFECT }

/**
 * [year] as 12 rows × 31 cells, index [month-1][day-1]. 0 = blank; MOOD: the day's mood 1..5;
 * PERFECT: 2 = perfect day, 1 = active (not perfect). Days that don't exist or haven't come yet stay 0.
 */
fun yearPixels(year: Int, mode: PixelMode, moods: Map<LocalDate, Int>, input: WeekInput, today: LocalDate): Array<IntArray> {
    val out = Array(12) { IntArray(31) }
    val first = LocalDate.of(year, 1, 1)
    val last = minOf(today, LocalDate.of(year, 12, 31))
    if (last < first) return out
    val perfect = if (mode == PixelMode.PERFECT)
        perfectDayOutcomes(input.habits.map { it.habit }, input.logs, first, last).filter { it.second == DayOutcome.SUCCESS }.map { it.first }.toSet()
    else emptySet()
    generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.forEach { d ->
        out[d.monthValue - 1][d.dayOfMonth - 1] = when (mode) {
            PixelMode.MOOD -> moods[d]?.coerceIn(0, 5) ?: 0
            PixelMode.PERFECT -> if (d in perfect) 2 else if (d in input.activeDays) 1 else 0
        }
    }
    return out
}

data class MoodRow(val label: String, val diff: Double)

private const val MOOD_WINDOW = 90L
private const val MOOD_DAYS_NEEDED = 30
private const val MOOD_SIDE_NEEDED = 5

private fun avgDiff(a: List<Int>, b: List<Int>): Double? =
    if (a.size < MOOD_SIDE_NEEDED || b.size < MOOD_SIDE_NEEDED) null
    else ((a.average() - b.average()) * 10).roundToInt() / 10.0

/**
 * Per habit: average mood on its done days minus on its missed days, over the 90 days before [today]; plus
 * "no journal" (days with a mood but no journal line, minus days with one). Null until 30 of those days have a
 * mood; a row needs 5 days on each side. Largest difference first.
 */
fun moodVsHabits(moods: Map<LocalDate, Int>, journalDays: Set<LocalDate>, input: WeekInput, today: LocalDate): List<MoodRow>? {
    val window = (1L..MOOD_WINDOW).map { today.minusDays(it) }.filter { it in moods }.toSet()
    if (window.size < MOOD_DAYS_NEEDED) return null
    val rows = input.habits.filter { !it.hidden }.sortedBy { it.sortOrder }.mapNotNull { h ->
        val outcomes = habitOutcomes(h.habit, input.logs[h.habit.id].orEmpty(), today).filter { it.first in window }
        val done = outcomes.filter { it.second == DayOutcome.SUCCESS }.map { moods.getValue(it.first) }
        val missed = outcomes.filter { it.second == DayOutcome.MISS }.map { moods.getValue(it.first) }
        avgDiff(done, missed)?.let { MoodRow(h.title, it) }
    }
    val noJournal = avgDiff(window.filter { it !in journalDays }.map { moods.getValue(it) }, window.filter { it in journalDays }.map { moods.getValue(it) })
    return (rows + listOfNotNull(noJournal?.let { MoodRow("no journal", it) })).sortedByDescending { abs(it.diff) }
}

data class Completion(val title: String, val percent: Int)

/** Per habit: done ÷ (done + missed) — a frozen day is a miss, today counts only once done. Last 30 days or all time. */
fun completion(input: WeekInput, today: LocalDate, last30: Boolean): List<Completion> {
    val from = if (last30) today.minusDays(29) else LocalDate.MIN
    return input.habits.filter { !it.hidden }.sortedBy { it.sortOrder }.mapNotNull { h ->
        val o = habitOutcomes(h.habit, input.logs[h.habit.id].orEmpty(), today).filter { it.first >= from }
        val done = o.count { it.second == DayOutcome.SUCCESS }
        val total = done + o.count { it.second == DayOutcome.MISS }
        if (total == 0) null else Completion(h.title, (100.0 * done / total).roundToInt())
    }.sortedByDescending { it.percent }
}
