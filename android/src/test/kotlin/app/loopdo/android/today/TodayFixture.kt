package app.loopdo.android.today

import app.loopdo.client.today.HabitRow
import app.loopdo.client.today.ListRow
import app.loopdo.client.today.LogRow
import app.loopdo.client.today.ScheduleRow
import app.loopdo.client.today.TaskRow
import app.loopdo.client.today.TodayModel
import app.loopdo.client.today.buildToday

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

/** The locked mockup's day (sat 14 nov, 00:42 sun IST, day end 04:00) for tests of anything built on TodayModel. */
object TodayFixture {
    val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    fun d(day: Int): LocalDate = LocalDate.of(2026, 11, day)
    val now: Instant = Instant.parse("2026-11-14T19:12:00Z")
    val dayEndAt: Instant = dayEndInstant(d(14), ist)
    private val since = d(1)
    private val daily = { h: String -> ScheduleRow(h, since, Recurrence.DAILY, emptySet()) }

    fun today(journalDays: Set<LocalDate> = emptySet()): TodayModel = buildToday(
        now = now, dateAt = { loopDoDate(it, ist) }, dayEndAt = dayEndAt, since = since,
        habits = listOf(
            HabitRow("water", "water", HabitKind.COUNT, 8, null, null, 0),
            HabitRow("gym", "gym", HabitKind.YES_NO, 1, LocalTime.of(18, 0), null, 1),
            HabitRow("med", "meditate", HabitKind.YES_NO, 1, null, null, 2),
            HabitRow("jp", "japanese 20m", HabitKind.YES_NO, 1, null, null, 3),
        ),
        schedules = listOf(daily("water"), daily("med"), daily("jp"),
            ScheduleRow("gym", since, Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))),
        logs = listOf(LogRow("water", d(14), 5), LogRow("med", d(14), 1), LogRow("jp", d(14), 1)),
        tasks = listOf(
            TaskRow("rent", null, "pay rent", d(13), null, null, 0),
            TaskRow("apply", "jh", "apply: 2 jobs", d(14), null, null, 1),
            TaskRow("call", null, "call home", d(14), null, d(14), 2),
            TaskRow("i1", null, "read compose docs", null, null, null, 3),
        ),
        lists = listOf(ListRow("jh", "job hunt", 0)),
        journalDays = journalDays, activeDays = setOf(d(14)),
    )
}
