package app.loopdo.android.today

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
data class HabitRow(val id: String, val title: String, val kind: HabitKind, val target: Int, val reminderTime: LocalTime?, val archivedOn: LocalDate?, val sortOrder: Int)
data class ScheduleRow(val habitId: String, val validFrom: LocalDate, val recurrence: Recurrence, val weekdays: Set<DayOfWeek>)
data class LogRow(val habitId: String, val date: LocalDate, val count: Int, val verified: Boolean = true)
data class TaskRow(val id: String, val listId: String?, val title: String, val dueDate: LocalDate?, val dueTime: LocalTime?, val completedOn: LocalDate?, val sortOrder: Int)
data class ListRow(val id: String, val name: String, val sortOrder: Int)

// What the today screen shows (docs/design/app, screen 1).
data class StreakLine(val journal: Int, val perfect: Int, val active: Int, val freezes: Int)
data class HabitItem(val id: String, val title: String, val kind: HabitKind, val count: Int, val target: Int, val streak: Int, val reminderTime: LocalTime?)
data class TaskItem(val id: String, val title: String, val lateSince: LocalDate?, val listName: String?, val dueTime: LocalTime?, val done: Boolean)
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
): TodayModel {
    val today = dateAt(now)
    val schedulesBy = schedules.groupBy { it.habitId }
    val logsBy = logs.groupBy { it.habitId }
    val shared = habits.map { h ->
        Habit(
            id = h.id, kind = h.kind, target = h.target, archivedOn = h.archivedOn,
            schedules = schedulesBy[h.id].orEmpty().map { HabitSchedule(it.validFrom, it.recurrence, it.weekdays) },
        )
    }
    val sharedLogs = logsBy.mapValues { (_, l) -> l.map { HabitLog(it.date, it.count, it.verified) } }

    val dueToday = habits.zip(shared).filter { (row, h) ->
        (row.archivedOn == null || today < row.archivedOn) && isDue(h.schedules, today)
    }.sortedWith(compareBy({ it.first.sortOrder }, { it.first.title }))
    val items = dueToday.map { (row, h) ->
        val count = logsBy[row.id].orEmpty().filter { it.date == today }.maxOfOrNull { it.count } ?: 0
        val streak = computeStreak(habitOutcomes(h, sharedLogs[row.id].orEmpty(), today)).current
        HabitItem(row.id, row.title, row.kind, count, row.target, streak, row.reminderTime)
    }

    val listNames = lists.associate { it.id to it.name }
    fun item(t: TaskRow) = TaskItem(t.id, t.title, lateSince(t.dueDate, t.completedOn, today), t.listId?.let(listNames::get), t.dueTime, t.completedOn != null)
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
        late = ordered.filter { states[it] == TaskState.LATE }.sortedBy { it.dueDate }.map(::item),
        habitsOpen = items.filter { it.count < it.target },
        habitsDone = items.filter { it.count >= it.target },
        dueToday = ordered.filter { t ->
            states[t] == TaskState.DUE_TODAY || (t.dueDate != null && t.completedOn == today)
        }.map(::item).sortedBy { it.done },
        inbox = ordered.filter { it.dueDate == null && it.listId == null && it.completedOn == null }.map(::item),
        lists = lists.sortedBy { it.sortOrder }.map { l ->
            ListGroup(l.id, l.name, ordered.filter { it.listId == l.id && it.dueDate == null && it.completedOn == null }.map(::item))
        },
    )
}
