package app.loopdo.android.habits

import app.loopdo.android.today.HabitRow
import app.loopdo.android.today.LogRow
import app.loopdo.android.today.ScheduleRow
import app.loopdo.android.today.short
import app.loopdo.shared.habit.DayOutcome
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.habit.habitOutcomes
import app.loopdo.shared.habit.isDue
import app.loopdo.shared.habit.scheduleOn
import app.loopdo.shared.habit.targetOn
import app.loopdo.shared.streak.computeStreak
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// What the habits tab, the habit detail and the habit editor show. Every judgement (due, done,
// missed, frozen, streaks) comes from the shared rules; this file only arranges it for the screens.

/** One day of the 7-day strip: ■ done, □ missed, ❄ saved by a freeze, ◦ not due, · today (not yet). */
enum class StripCell { DONE, MISSED, FROZEN, NOT_DUE, TODAY }

/** The last [days] days, oldest first, ending today. */
fun habitStrip(habit: Habit, logs: List<HabitLog>, today: LocalDate, days: Int = 7): List<StripCell> {
    val records = habitOutcomes(habit, logs, today)
    val frozen = computeStreak(records).frozenDays
    val byDate = records.toMap()
    return (days - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { date ->
        when (byDate[date]) {
            DayOutcome.SUCCESS -> StripCell.DONE
            DayOutcome.MISS -> if (date in frozen) StripCell.FROZEN else StripCell.MISSED
            DayOutcome.PENDING -> StripCell.TODAY
            DayOutcome.NEUTRAL, null -> StripCell.NOT_DUE
        }
    }
}

/** A row of the habits tab. [note]: "until thu 19 nov" (paused) or "since tue 10 nov" (archived). */
data class HabitLine(val id: String, val title: String, val strip: List<StripCell>, val streak: Int, val note: String?)

data class HabitsList(
    val today: LocalDate,
    val stripDays: List<LocalDate>,
    val active: List<HabitLine>,
    val paused: List<HabitLine>,
    val archived: List<HabitLine>,
)

/** The habits tab: active habits in the user's order, then paused, then archived. Deleted-forever habits are hidden. */
fun buildHabitsList(habits: List<HabitRow>, schedules: List<ScheduleRow>, logs: List<LogRow>, today: LocalDate): HabitsList {
    val active = mutableListOf<HabitLine>()
    val paused = mutableListOf<HabitLine>()
    val archived = mutableListOf<HabitLine>()
    habits.filter { it.deletedAt == null }.sortedWith(compareBy({ it.sortOrder }, { it.title })).forEach { row ->
        val habit = row.toHabit(schedules)
        val mine = logs.of(row.id)
        val line = { note: String? ->
            HabitLine(row.id, row.title, habitStrip(habit, mine, today), computeStreak(habitOutcomes(habit, mine, today)).current, note)
        }
        val pause = pauseOf(habit.schedules, today)
        when {
            row.archivedOn != null -> archived += line("since ${row.archivedOn.short()}")
            pause != null && pause.started -> paused += line(pause.until?.let { "until ${it.short()}" } ?: "paused")
            else -> active += line(null)
        }
    }
    return HabitsList(today, (6 downTo 0).map { today.minusDays(it.toLong()) }, active, paused, archived)
}

/** Month heatmap cell: filled = done, red outline = missed, ❄ = saved by a freeze, dim = not due, very dim = future. */
enum class CellState { DONE, MISSED, FROZEN, OFF, PENDING, FUTURE }
data class MonthCell(val date: LocalDate, val state: CellState, val today: Boolean)

/** Locked app-screens-v2, screen 2. */
data class HabitDetail(
    val id: String,
    val title: String,
    /** `mon · wed · fri @18:00`, `every day`, or `paused until thu 19 nov`. */
    val scheduleLine: String,
    val streak: Int,
    val best: Int,
    val freezes: Int,
    /** Done days out of due days so far (null before any due day closed). */
    val donePercent: Int?,
    val month: YearMonth,
    val cells: List<MonthCell>,
    val monthDone: Int,
    val monthDue: Int,
    val monthFrozen: Int,
    val nextDue: LocalDate?,
    val reminder: LocalTime?,
    /** More done days until the next ❄ (null = both freezes held). */
    val toNextFreeze: Int?,
    val since: LocalDate?,
    val totalDone: Int,
)

fun habitDetail(habit: HabitRow, schedules: List<ScheduleRow>, logs: List<LogRow>, today: LocalDate, month: YearMonth): HabitDetail? {
    if (habit.deletedAt != null) return null
    val h = habit.toHabit(schedules)
    val records = habitOutcomes(h, logs.of(habit.id), today)
    val streak = computeStreak(records)
    val byDate = records.toMap()
    val done = records.count { it.second == DayOutcome.SUCCESS }
    val missed = records.count { it.second == DayOutcome.MISS }
    val cells = (1..month.lengthOfMonth()).map { month.atDay(it) }.map { date ->
        val state = when {
            date > today -> CellState.FUTURE
            else -> when (byDate[date]) {
                DayOutcome.SUCCESS -> CellState.DONE
                DayOutcome.MISS -> if (date in streak.frozenDays) CellState.FROZEN else CellState.MISSED
                DayOutcome.PENDING -> CellState.PENDING
                DayOutcome.NEUTRAL, null -> CellState.OFF
            }
        }
        MonthCell(date, state, date == today)
    }
    val nextDue = if (habit.archivedOn != null) null else generateSequence(today) { it.plusDays(1) }.take(400).firstOrNull { date ->
        isDue(h.schedules, date) && (date != today || byDate[today] == DayOutcome.PENDING)
    }
    return HabitDetail(
        id = habit.id, title = habit.title, scheduleLine = scheduleLine(h.schedules, habit.reminderTime, today),
        streak = streak.current, best = streak.best, freezes = streak.freezesHeld,
        donePercent = if (done + missed == 0) null else (done * 100.0 / (done + missed)).roundToInt(),
        month = month, cells = cells,
        monthDone = cells.count { it.state == CellState.DONE },
        monthDue = cells.count { it.state in setOf(CellState.DONE, CellState.MISSED, CellState.FROZEN) },
        monthFrozen = cells.count { it.state == CellState.FROZEN },
        nextDue = nextDue, reminder = habit.reminderTime, toNextFreeze = streak.daysToNextFreeze,
        since = h.schedules.minOfOrNull { it.validFrom }, totalDone = done,
    )
}

/** A pause still running or ahead: from [from] through [until] (null = no end yet). */
data class PauseInfo(val from: LocalDate, val until: LocalDate?, val started: Boolean)

/** What the editor opens with. [draft] = the habit as it will be from tomorrow (pending changes included). */
data class EditorStart(
    val draft: HabitDraft,
    /** Today's target, which a change can't touch. */
    val todayTarget: Int,
    /** Any log: the type is frozen (it would re-judge past days). */
    val hasHistory: Boolean,
    val pause: PauseInfo? = null,
    val archivedOn: LocalDate? = null,
)

fun editorStart(habit: HabitRow, schedules: List<ScheduleRow>, logs: List<LogRow>, today: LocalDate): EditorStart {
    val h = habit.toHabit(schedules)
    val planned = h.schedules.lastOrNull { it.recurrence != Recurrence.PAUSED }
    return EditorStart(
        draft = HabitDraft(
            title = habit.title, kind = habit.kind, target = planned?.target ?: habit.target, unit = habit.unit, step = habit.step,
            recurrence = planned?.recurrence ?: Recurrence.DAILY, weekdays = planned?.weekdays.orEmpty(), reminderTime = habit.reminderTime,
        ),
        todayTarget = targetOn(h, today),
        hasHistory = logs.any { it.habitId == habit.id },
        pause = pauseOf(h.schedules, today),
        archivedOn = habit.archivedOn,
    )
}

/** `35 → 50 from tomorrow, tue 17 nov. today and every past day keep 35.` (locked screen 4), or null if unchanged. */
fun targetChangeNote(current: Int, new: Int, today: LocalDate): String? =
    if (new == current) null else "$current → $new from tomorrow, ${today.plusDays(1).short()}. today and every past day keep $current."

/** The pause still running or ahead in [sorted] versions: (paused, resume or null). An ended pause is history. */
internal fun <T> currentPause(sorted: List<T>, today: LocalDate, schedule: (T) -> HabitSchedule): Pair<T, T?>? {
    val i = sorted.indexOfLast { schedule(it).recurrence == Recurrence.PAUSED }
    if (i < 0) return null
    val resume = sorted.getOrNull(i + 1)
    return if (resume == null || schedule(resume).validFrom > today) sorted[i] to resume else null
}

private fun pauseOf(schedules: List<HabitSchedule>, today: LocalDate): PauseInfo? =
    currentPause(schedules.sortedBy { it.validFrom }, today) { it }?.let { (p, r) ->
        PauseInfo(p.validFrom, r?.validFrom?.minusDays(1), p.validFrom <= today)
    }

private fun scheduleLine(schedules: List<HabitSchedule>, reminder: LocalTime?, today: LocalDate): String {
    val s = scheduleOn(schedules, today) ?: schedules.minByOrNull { it.validFrom } ?: return ""
    val days = when (s.recurrence) {
        Recurrence.DAILY -> "every day"
        Recurrence.WEEKDAYS -> s.weekdays.sorted().joinToString(" · ") { it.short() }
        Recurrence.PAUSED -> return pauseOf(schedules, today)?.until?.let { "paused until ${it.short()}" } ?: "paused"
    }
    return days + (reminder?.let { " @$it" } ?: "")
}

internal fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase(Locale.ENGLISH)

private fun HabitRow.toHabit(schedules: List<ScheduleRow>) = Habit(
    id = id, kind = kind, target = target, archivedOn = archivedOn,
    schedules = schedules.filter { it.habitId == id }.sortedBy { it.validFrom }.map { HabitSchedule(it.validFrom, it.recurrence, it.weekdays, it.target) },
)

private fun List<LogRow>.of(id: String) = filter { it.habitId == id }.map { HabitLog(it.date, it.count, it.verified) }
