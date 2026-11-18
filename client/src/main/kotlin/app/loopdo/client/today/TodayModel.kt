package app.loopdo.client.today

import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.habit.activeDayOutcomes
import app.loopdo.shared.habit.habitOutcomes
import app.loopdo.shared.habit.isDue
import app.loopdo.shared.habit.journalOutcomes
import app.loopdo.shared.habit.perfectDayOutcomes
import app.loopdo.shared.habit.targetOn
import app.loopdo.shared.streak.computeStreak
import app.loopdo.shared.task.TaskState
import app.loopdo.shared.task.lateSince
import app.loopdo.shared.task.taskState
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// Rows as read from the phone's database.
data class HabitRow(
    val id: String, val title: String, val kind: HabitKind, val target: Int, val reminderTime: LocalTime?, val archivedOn: LocalDate?, val sortOrder: Int,
    /** [+step] for count habits; unit as in "20/35 words"; deletedAt = "delete forever" (hidden, history still counts). */
    val step: Int = 1, val unit: String? = null, val deletedAt: Instant? = null,
)
data class ScheduleRow(val habitId: String, val validFrom: LocalDate, val recurrence: Recurrence, val weekdays: Set<DayOfWeek>, val target: Int? = null)
data class LogRow(val habitId: String, val date: LocalDate, val count: Int, val verified: Boolean = true)
/**
 * [templateId] / [occurrence]: a repeating task's copy; [seriesDoneThrough] = the latest occurrence of its series
 * that was ticked (older open copies stay as misses, owner 2026-11-18).
 */
data class TaskRow(
    val id: String, val listId: String?, val title: String, val dueDate: LocalDate?, val dueTime: LocalTime?, val completedOn: LocalDate?, val sortOrder: Int,
    val templateId: String? = null, val occurrence: LocalDate? = null, val seriesDoneThrough: LocalDate? = null,
)
data class ListRow(val id: String, val name: String, val sortOrder: Int)

// What the today screen shows (docs/design/app, screen 1).
data class StreakLine(val journal: Int, val perfect: Int, val active: Int, val freezes: Int)
/** [target] = the target of today (a change applies from tomorrow); [step] = what [+n] adds. */
data class HabitItem(
    val id: String, val title: String, val kind: HabitKind, val count: Int, val target: Int, val streak: Int, val reminderTime: LocalTime?,
    val step: Int = 1, val unit: String? = null,
    /** M8: this week's focus on this habit — done days so far / the goal (null = not a focus). */
    val focus: FocusMark? = null,
)

/** M8: a focus habit's progress this week ("2/4 this week" on Today). */
data class FocusMark(val done: Int, val goal: Int)
/** [listId] and [due] are for the long-press menu (M5). */
data class TaskItem(
    val id: String, val title: String, val lateSince: LocalDate?, val listName: String?, val dueTime: LocalTime?, val done: Boolean,
    val listId: String? = null, val due: LocalDate? = null,
    /** A repeating task's other missed copies (shown as "missed N" on its one late copy). */
    val missed: Int = 0,
    /** M8: a focus of this week (an accent `›` and a dim "focus" on Today). */
    val focus: Boolean = false,
)
data class ListGroup(val id: String, val name: String, val items: List<TaskItem>)

data class TodayModel(
    val date: LocalDate,
    val endsIn: Duration,
    val streaks: StreakLine,
    val late: List<TaskItem>,
    val habitsOpen: List<HabitItem>,
    val habitsDone: List<HabitItem>,
    val dueToday: List<TaskItem>,
    val inbox: List<TaskItem>,
    val lists: List<ListGroup>,
    /** Today already has a journal day (text or photo): the widget shows [✓ journal]. */
    val journalToday: Boolean = false,
)

/**
 * Today's sections, using only the shared rules (the server judges with the same ones).
 * [dateAt] resolves the user's LoopDo date at any instant from their synced settings
 * history; [dayEndAt] is when today ends; [since] is the day the user started.
 */
fun buildToday(
    now: Instant,
    dateAt: (Instant) -> LocalDate,
    dayEndAt: Instant,
    since: LocalDate,
    habits: List<HabitRow>,
    schedules: List<ScheduleRow>,
    logs: List<LogRow>,
    tasks: List<TaskRow>,
    lists: List<ListRow>,
    journalDays: Set<LocalDate>,
    activeDays: Set<LocalDate>,
    /** M8: the live weekly focus rows (any week; only this week's are marked). */
    focus: List<app.loopdo.shared.stats.Focus> = emptyList(),
): TodayModel {
    val today = dateAt(now)
    val thisWeek = focus.filter { it.weekStart == app.loopdo.shared.stats.weekStart(today) }
    val schedulesBy = schedules.groupBy { it.habitId }
    val logsBy = logs.groupBy { it.habitId }
    val shared = habits.map { h ->
        Habit(
            id = h.id, kind = h.kind, target = h.target, archivedOn = h.archivedOn,
            schedules = schedulesBy[h.id].orEmpty().map { HabitSchedule(it.validFrom, it.recurrence, it.weekdays, it.target) },
        )
    }
    val sharedLogs = logsBy.mapValues { (_, l) -> l.map { HabitLog(it.date, it.count, it.verified) } }

    val dueToday = habits.zip(shared).filter { (row, h) ->
        // Hidden ("delete forever") habits leave the screen, but [shared] keeps them for perfect days.
        row.deletedAt == null && (row.archivedOn == null || today < row.archivedOn) && isDue(h.schedules, today)
    }.sortedWith(compareBy({ it.first.sortOrder }, { it.first.title }))
    val items = dueToday.map { (row, h) ->
        val count = logsBy[row.id].orEmpty().filter { it.date == today }.maxOfOrNull { it.count } ?: 0
        val streak = computeStreak(habitOutcomes(h, sharedLogs[row.id].orEmpty(), today)).current
        val mark = thisWeek.firstOrNull { it.kind == app.loopdo.shared.stats.FocusKind.HABIT && it.itemId == row.id }?.let { f ->
            val input = app.loopdo.shared.stats.WeekInput(listOf(app.loopdo.shared.stats.StatHabit(h, row.title, row.sortOrder)), sharedLogs, emptyList(), emptySet())
            (app.loopdo.shared.stats.focusResult(f, input, today) as app.loopdo.shared.stats.FocusResult.Habit).let { FocusMark(it.done, it.goal) }
        }
        HabitItem(row.id, row.title, row.kind, count, targetOn(h, today), streak, row.reminderTime, row.step, row.unit, mark)
    }

    val listNames = lists.associate { it.id to it.name }
    val focusTasks = thisWeek.filter { it.kind == app.loopdo.shared.stats.FocusKind.TASK }.map { it.itemId }.toSet()
    fun item(t: TaskRow) = TaskItem(
        t.id, t.title, lateSince(t.dueDate, t.completedOn, today), t.listId?.let(listNames::get), t.dueTime, t.completedOn != null, t.listId, t.dueDate,
        focus = t.id in focusTasks,
    )
    val ordered = tasks.sortedWith(compareBy({ it.sortOrder }, { it.title }))
    val states = ordered.associateWith { taskState(it.dueDate, it.completedOn, today) }

    val journal = computeStreak(journalOutcomes(journalDays, since, today))
    return TodayModel(
        date = today,
        endsIn = Duration.between(now, dayEndAt).coerceAtLeast(Duration.ZERO),
        streaks = StreakLine(
            journal = journal.current,
            perfect = computeStreak(perfectDayOutcomes(shared, sharedLogs, since, today)).current,
            active = computeStreak(activeDayOutcomes(activeDays, since, today)).current,
            freezes = journal.freezesHeld,
        ),
        late = lateTasks(ordered.filter { states[it] == TaskState.LATE }, ::item),
        habitsOpen = items.filter { it.count < it.target },
        habitsDone = items.filter { it.count >= it.target },
        dueToday = ordered.filter { t ->
            states[t] == TaskState.DUE_TODAY || (t.dueDate != null && t.completedOn == today)
        }.map(::item).sortedBy { it.done },
        inbox = ordered.filter { it.dueDate == null && it.listId == null && it.completedOn == null }.map(::item),
        lists = lists.sortedBy { it.sortOrder }.map { l ->
            ListGroup(l.id, l.name, ordered.filter { it.listId == l.id && it.dueDate == null && it.completedOn == null }.map(::item))
        },
        journalToday = today in journalDays,
    )
}

/**
 * Owner, 2026-11-18: a repeating task missed for days shows ONE late copy, the newest, with "missed N" (the
 * other open copies since the series was last ticked). Copies older than the last ticked one stay as misses
 * (every copy is kept for the stats) and are not shown again. One-off tasks are listed as they are.
 */
fun lateTasks(late: List<TaskRow>, item: (TaskRow) -> TaskItem): List<TaskItem> {
    val (copies, single) = late.partition { it.templateId != null }
    val open = copies.filter { c -> c.seriesDoneThrough == null || c.occurrence == null || c.occurrence > c.seriesDoneThrough }
    val collapsed = open.groupBy { it.templateId }.values.map { series ->
        val newest = series.maxBy { it.dueDate!! }
        newest to item(newest).copy(missed = series.size - 1)
    }
    return (single.map { it to item(it) } + collapsed).sortedBy { it.first.dueDate }.map { it.second }
}
