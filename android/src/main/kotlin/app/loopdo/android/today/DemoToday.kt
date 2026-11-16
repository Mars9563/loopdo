package app.loopdo.android.today

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.dayEndInstant
import app.loopdo.shared.day.loopDoDate
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Debug builds only (`am start ... --ez demo true`): the locked mockup's sample day,
 * to compare the real screen with the design without writing to anyone's data.
 */
fun demoToday(): TodayModel {
    val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    val now = Instant.parse("2026-11-14T19:12:00Z")
    fun d(day: Int) = LocalDate.of(2026, 11, day)
    val since = d(1)
    val daily = { h: String -> ScheduleRow(h, since, Recurrence.DAILY, emptySet()) }
    val ids = (0..30).map { java.util.UUID.nameUUIDFromBytes("demo$it".toByteArray()).toString() }
    return buildToday(
        now = now, dateAt = { loopDoDate(it, ist) }, dayEndAt = dayEndInstant(d(14), ist), since = since,
        habits = listOf(
            HabitRow(ids[0], "water", HabitKind.COUNT, 8, null, null, 0),
            HabitRow(ids[1], "gym", HabitKind.YES_NO, 1, LocalTime.of(18, 0), null, 1),
            HabitRow(ids[2], "meditate", HabitKind.YES_NO, 1, null, null, 2),
            HabitRow(ids[3], "japanese 20m", HabitKind.YES_NO, 1, null, null, 3),
        ),
        schedules = listOf(daily(ids[0]), daily(ids[2]), daily(ids[3]),
            ScheduleRow(ids[1], since, Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))),
        logs = listOf(LogRow(ids[0], d(14), 5), LogRow(ids[2], d(14), 1), LogRow(ids[3], d(14), 1)),
        tasks = listOf(
            TaskRow(ids[4], null, "pay rent", d(13), null, null, 0),
            TaskRow(ids[5], ids[20], "apply: 2 jobs", d(14), null, null, 1),
            TaskRow(ids[6], null, "call home", d(14), null, d(14), 2),
            TaskRow(ids[7], null, "read compose docs", null, null, null, 3),
            TaskRow(ids[8], null, "fix bike brake", null, null, null, 4),
            TaskRow(ids[9], null, "renew passport", null, null, null, 5),
            TaskRow(ids[10], null, "back up release key", null, null, null, 6),
        ) + (1..3).map { TaskRow(ids[10 + it], ids[21], "work $it", null, null, null, 10 + it) } +
            (1..5).map { TaskRow(ids[22 + it], ids[20], "job $it", null, null, null, 20 + it) },
        lists = listOf(ListRow(ids[21], "work", 0), ListRow(ids[20], "job hunt", 1)),
        journalDays = (1..14).map(::d).toSet(), activeDays = (1..14).map(::d).toSet(),
    )
}

/** Taps do nothing in the demo. */
object DemoUi : TodayUi {
    override fun tick(h: HabitItem) = Unit
    override fun untick(h: HabitItem) = Unit
    override fun decrement(h: HabitItem) = Unit
    override fun complete(h: HabitItem) = Unit
    override fun setCount(h: HabitItem, count: Int) = Unit
    override fun toggleTask(t: TaskItem) = Unit
    override fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: String?, listName: String?, today: LocalDate, onSaved: () -> Unit) = Unit
    override fun appendJournal(text: String, onSaved: () -> Unit) = Unit
    override fun undo(s: Snack) = Unit
    override fun dismissSnack(id: Long) = Unit
}
