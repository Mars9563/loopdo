package app.loopdo.android.today

import app.loopdo.client.today.FocusMark
import app.loopdo.client.today.HabitRow
import app.loopdo.client.today.ListRow
import app.loopdo.client.today.LogRow
import app.loopdo.client.today.ScheduleRow
import app.loopdo.client.today.TaskRow
import app.loopdo.client.today.buildToday

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.day.dateResolver
import app.loopdo.shared.day.dayEndInstant
import app.loopdo.shared.day.loopDoDate
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The locked today mockup (docs/design/app, screen 1) as data: Sat 14 Nov at 00:42 Sun IST. */
class TodayModelTest {
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    private val istAt: (Instant) -> LocalDate = { loopDoDate(it, ist) }
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private val since = d(1)
    private val now = Instant.parse("2026-11-14T19:12:00Z")

    private val daily = { h: String -> ScheduleRow(h, since, Recurrence.DAILY, emptySet()) }
    private val habits = listOf(
        HabitRow("water", "water", HabitKind.COUNT, 8, null, null, 0),
        HabitRow("gym", "gym", HabitKind.YES_NO, 1, LocalTime.of(18, 0), null, 1),
        HabitRow("med", "meditate", HabitKind.YES_NO, 1, null, null, 2),
        HabitRow("jp", "japanese 20m", HabitKind.YES_NO, 1, null, null, 3),
    )
    private val schedules = listOf(
        daily("water"), daily("med"), daily("jp"),
        ScheduleRow("gym", since, Recurrence.WEEKDAYS, setOf(MONDAY, WEDNESDAY, FRIDAY, SATURDAY)),
    )
    private val logs = listOf(LogRow("water", d(14), 5), LogRow("med", d(14), 1), LogRow("jp", d(14), 1))
    private val lists = listOf(ListRow("work", "work", 0), ListRow("jh", "job hunt", 1))
    private val tasks = listOf(
        TaskRow("rent", null, "pay rent", d(13), null, null, 0),
        TaskRow("apply", "jh", "apply: 2 jobs", d(14), null, null, 1),
        TaskRow("call", null, "call home", d(14), null, d(14), 2),
        TaskRow("i1", null, "read compose docs", null, null, null, 3),
        TaskRow("i2", null, "fix bike brake", null, null, null, 4),
        TaskRow("i3", null, "renew passport", null, null, null, 5),
        TaskRow("i4", null, "back up release key", null, null, null, 6),
    ) + (1..3).map { TaskRow("w$it", "work", "work $it", null, null, null, 10 + it) } +
        (1..5).map { TaskRow("j$it", "jh", "job $it", null, null, null, 20 + it) }

    private fun build(at: Instant = now, dateAt: (Instant) -> LocalDate = istAt, settings: DaySettings = ist) = buildToday(
        now = at, dateAt = dateAt, dayEndAt = dayEndInstant(dateAt(at), settings), since = since,
        habits = habits, schedules = schedules, logs = logs, tasks = tasks, lists = lists,
        journalDays = emptySet(), activeDays = setOf(d(14)),
    )

    @Test
    fun matchesLockedMockup() {
        val m = build()
        assertEquals(d(14), m.date)
        assertEquals(Duration.ofHours(3).plusMinutes(18), m.endsIn)
        assertEquals(listOf("pay rent" to d(13)), m.late.map { it.title to it.lateSince })
        assertEquals(listOf("water", "gym"), m.habitsOpen.map { it.title })
        assertEquals(listOf("meditate", "japanese 20m"), m.habitsDone.map { it.title })
        assertEquals(listOf("apply: 2 jobs" to "job hunt"), m.dueToday.filter { !it.done }.map { it.title to it.listName })
        assertEquals(listOf("call home"), m.dueToday.filter { it.done }.map { it.title })
        assertEquals(4, m.inbox.size)
        assertEquals(listOf("work" to 3, "job hunt" to 5), m.lists.map { it.name to it.items.size })
    }

    @Test
    fun countHabitShowsProgress() {
        val water = build().habitsOpen.first { it.id == "water" }
        assertEquals(5 to 8, water.count to water.target)
    }

    @Test
    fun offDayHabitHidden() { // Sunday is not a gym day
        assertTrue(build(at = Instant.parse("2026-11-15T05:00:00Z")).habitsOpen.none { it.id == "gym" })
    }

    @Test
    fun todayRollsOverAtDayEnd() {
        val before = build(at = Instant.parse("2026-11-14T22:29:00Z"))
        val after = build(at = Instant.parse("2026-11-14T22:30:00Z"))
        assertEquals(d(14), before.date)
        assertEquals(d(15), after.date)
        assertTrue(before.habitsOpen.any { it.id == "gym" }) // Saturday gym still open at 03:59
        assertTrue(after.habitsOpen.none { it.id == "gym" }) // Sunday: yesterday's gym can't be ticked here
        assertEquals(0, after.habitsOpen.first { it.id == "water" }.count) // a fresh day
    }

    @Test
    fun todayUsesSyncedSettingsHistory() {
        // Moved to New York (in force Sat 22:30 UTC). At 03:00 UTC Sun it is 22:00 Sat in NY.
        val ny = DaySettings(ZoneId.of("America/New_York"), LocalTime.of(4, 0))
        val resolver = dateResolver(listOf(DaySettingsVersion(Instant.EPOCH, ist, null), DaySettingsVersion(Instant.parse("2026-11-14T22:30:00Z"), ny, d(14))))
        assertEquals(d(14), build(at = Instant.parse("2026-11-15T03:00:00Z"), dateAt = resolver, settings = ny).date)
    }

    @Test
    fun streakLineCountsActiveDays() {
        assertEquals(1, build().streaks.active) // ticked today
    }

    @Test
    fun journalTodayFollowsJournalDays() {
        fun with(days: Set<LocalDate>) = buildToday(
            now = now, dateAt = istAt, dayEndAt = dayEndInstant(d(14), ist), since = since,
            habits = habits, schedules = schedules, logs = logs, tasks = tasks, lists = lists,
            journalDays = days, activeDays = emptySet(),
        )
        assertTrue(with(setOf(d(14))).journalToday)
        assertTrue(!with(setOf(d(13))).journalToday)
    }

    @Test
    fun countRowShowsUnitStepAndTheTargetOfToday() {
        val vocab = HabitRow("vocab", "japanese vocab", HabitKind.COUNT, 35, null, null, 9, step = 5, unit = "words")
        val m = buildToday(
            now = now, dateAt = istAt, dayEndAt = dayEndInstant(d(14), ist), since = since,
            habits = listOf(vocab), schedules = listOf(daily("vocab"), ScheduleRow("vocab", d(14), Recurrence.DAILY, emptySet(), target = 40)),
            logs = listOf(LogRow("vocab", d(14), 20)), tasks = emptyList(), lists = emptyList(), journalDays = emptySet(), activeDays = emptySet(),
        )
        val item = m.habitsOpen.single()
        assertEquals(Triple(20, 40, 5), Triple(item.count, item.target, item.step))
        assertEquals("words", item.unit)
    }

    @Test
    fun hiddenHabitIsNotShownTodayButStillCounts() {
        val hidden = habits.map { if (it.id == "gym") it.copy(deletedAt = Instant.parse("2026-11-14T10:00:00Z")) else it }
        val m = buildToday(
            now = now, dateAt = istAt, dayEndAt = dayEndInstant(d(14), ist), since = since,
            habits = hidden, schedules = schedules, logs = logs, tasks = tasks, lists = lists, journalDays = emptySet(), activeDays = setOf(d(14)),
        )
        assertTrue((m.habitsOpen + m.habitsDone).none { it.id == "gym" })
        assertEquals(build().streaks.perfect, m.streaks.perfect) // the hidden gym still counts for perfect days
    }

    // Owner, 2026-11-18: a daily series missed for days shows ONE late copy with "missed N"; every copy stays (stats).
    @Test
    fun missedDaysCollapseIntoOneLateCopy() {
        val series = (8..13).map { day -> TaskRow("w$day", null, "water plants", d(day), null, null, 10, templateId = "w", occurrence = d(day)) }
        val m = buildToday(
            now = now, dateAt = istAt, dayEndAt = dayEndInstant(d(14), ist), since = since,
            habits = habits, schedules = schedules, logs = logs, tasks = tasks + series, lists = lists,
            journalDays = emptySet(), activeDays = setOf(d(14)),
        )
        val water = m.late.filter { it.title == "water plants" }
        assertEquals(listOf("w13" to 5), water.map { it.id to it.missed }) // the newest copy, 5 others missed
        assertEquals(listOf("pay rent"), m.late.filter { it.title != "water plants" }.map { it.title }) // one-off tasks unchanged
    }

    @Test
    fun copiesBeforeTheLastTickedOneStayMissed() {
        // Ticking the late copy (the 13th) leaves the older ones as misses: they don't come back one by one.
        val series = (8..12).map { day -> TaskRow("w$day", null, "water plants", d(day), null, null, 10, templateId = "w", occurrence = d(day), seriesDoneThrough = d(13)) } +
            TaskRow("w13", null, "water plants", d(13), null, d(14), 10, templateId = "w", occurrence = d(13), seriesDoneThrough = d(13))
        val m = buildToday(
            now = now, dateAt = istAt, dayEndAt = dayEndInstant(d(14), ist), since = since,
            habits = habits, schedules = schedules, logs = logs, tasks = tasks + series, lists = lists,
            journalDays = emptySet(), activeDays = setOf(d(14)),
        )
        assertEquals(emptyList<String>(), m.late.filter { it.title == "water plants" }.map { it.id })
    }

    // --- M8: the weekly focus marked on Today (locked weekly-review-v1, mock C) ---------------------------------

    private val week = d(9) // mon 9 – sun 15 nov
    private fun withFocus(vararg f: app.loopdo.shared.stats.Focus, extraLogs: List<LogRow> = emptyList()) = buildToday(
        now = now, dateAt = istAt, dayEndAt = dayEndInstant(d(14), ist), since = since,
        habits = habits, schedules = schedules, logs = logs + extraLogs, tasks = tasks, lists = lists,
        journalDays = emptySet(), activeDays = emptySet(), focus = f.toList(),
    )

    @Test
    fun focusHabitGetsWeekProgress() {
        val f = app.loopdo.shared.stats.Focus("f", week, app.loopdo.shared.stats.FocusKind.HABIT, "gym", "gym", 4, null)
        val m = withFocus(f, extraLogs = listOf(LogRow("gym", d(9), 1), LogRow("gym", d(11), 1)))
        assertEquals(FocusMark(2, 4), m.habitsOpen.single { it.id == "gym" }.focus)
        assertEquals(null, m.habitsOpen.single { it.id == "water" }.focus)
    }

    @Test
    fun focusTaskMarked() {
        val f = app.loopdo.shared.stats.Focus("f", week, app.loopdo.shared.stats.FocusKind.TASK, "i2", "fix bike brake", null, d(15))
        val m = withFocus(f)
        assertTrue(m.inbox.single { it.id == "i2" }.focus)
        assertEquals(listOf("i2"), m.inbox.filter { it.focus }.map { it.id })
    }

    @Test
    fun lastWeeksFocusNotMarked() {
        val f = app.loopdo.shared.stats.Focus("f", week.minusDays(7), app.loopdo.shared.stats.FocusKind.HABIT, "gym", "gym", 4, null)
        assertEquals(null, withFocus(f).habitsOpen.single { it.id == "gym" }.focus)
    }
}
