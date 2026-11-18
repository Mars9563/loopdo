package app.loopdo.shared.remind

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.task.RepeatRule
import app.loopdo.shared.task.instanceId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemindersTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val d18 = LocalDate.of(2026, 11, 18)
    private val d19 = LocalDate.of(2026, 11, 19)
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Instant = LocalDateTime.of(y, m, d, h, min).atZone(ist).toInstant()
    private val noon = at(2026, 11, 18, 12)
    private val versions = listOf(DaySettingsVersion(Instant.EPOCH, DaySettings(ist, LocalTime.of(4, 0)), null))
    private val daily = listOf(HabitSchedule(LocalDate.of(2026, 1, 1), Recurrence.DAILY))
    private fun habit(id: String, time: String?, schedules: List<HabitSchedule> = daily, archivedOn: LocalDate? = null, deleted: Boolean = false) =
        HabitPlan(id, time?.let(LocalTime::parse), schedules, archivedOn, deleted)
    private fun input(
        habits: List<HabitPlan> = emptyList(), done: Set<Pair<String, LocalDate>> = emptySet(), tasks: List<TaskPlan> = emptyList(),
        series: List<SeriesPlan> = emptyList(), journal: Set<LocalDate> = setOf(d18, d19), v: List<DaySettingsVersion> = versions,
        prefs: ReminderPrefs = ReminderPrefs(),
    ) = ReminderInput(v, habits, done, tasks, series, journal, prefs)
    private fun List<Reminder>.of(kind: ReminderKind) = filter { it.kind == kind }

    @Test fun habitPingsTenBeforeAndAt() {
        val rs = planReminders(noon, input(habits = listOf(habit("gym", "18:00")))).of(ReminderKind.HABIT)
        assertEquals(
            listOf(
                Triple(d18, Ping.BEFORE, at(2026, 11, 18, 17, 50)), Triple(d18, Ping.AT, at(2026, 11, 18, 18)),
                Triple(d19, Ping.BEFORE, at(2026, 11, 19, 17, 50)), Triple(d19, Ping.AT, at(2026, 11, 19, 18)),
            ),
            rs.map { Triple(it.date, it.ping, it.fireAt) },
        )
        assertTrue(rs.all { it.itemId == "gym" })
    }

    @Test fun twoAmBelongsToTheNightOfTheDay() {
        val rs = planReminders(noon, input(habits = listOf(habit("late", "02:00")))).of(ReminderKind.HABIT)
        assertEquals(at(2026, 11, 19, 2), rs.first { it.date == d18 && it.ping == Ping.AT }.fireAt)
    }

    @Test fun beforePingBeforeTheDayStartIsDropped() {
        val rs = planReminders(noon, input(habits = listOf(habit("early", "04:05")))).of(ReminderKind.HABIT)
        // 11-18's 04:05 is already past at noon; 11-19's BEFORE (03:55) belongs to the day before.
        assertEquals(listOf(d19 to Ping.AT), rs.map { it.date to it.ping })
    }

    @Test fun pastPingsAreNotPlanned() {
        val rs = planReminders(at(2026, 11, 18, 17, 55), input(habits = listOf(habit("gym", "18:00")))).of(ReminderKind.HABIT)
        assertEquals(listOf(d18 to Ping.AT, d19 to Ping.BEFORE, d19 to Ping.AT), rs.map { it.date to it.ping })
    }

    @Test fun doneHabitIsNotPlanned() {
        val rs = planReminders(noon, input(habits = listOf(habit("gym", "18:00")), done = setOf("gym" to d18))).of(ReminderKind.HABIT)
        assertEquals(setOf(d19), rs.map { it.date }.toSet())
    }

    @Test fun weekdayScheduleOnlyOnItsDays() {
        val wednesdays = listOf(HabitSchedule(LocalDate.of(2026, 1, 1), Recurrence.WEEKDAYS, setOf(DayOfWeek.WEDNESDAY)))
        val rs = planReminders(noon, input(habits = listOf(habit("w", "18:00", wednesdays)))).of(ReminderKind.HABIT)
        assertEquals(setOf(d18), rs.map { it.date }.toSet()) // 11-18 is a Wednesday, 11-19 a Thursday
    }

    @Test fun pausedArchivedDeletedHabitsNeverPing() {
        val paused = listOf(HabitSchedule(LocalDate.of(2026, 1, 1), Recurrence.PAUSED))
        val rs = planReminders(
            noon,
            input(habits = listOf(habit("p", "18:00", paused), habit("a", "18:00", archivedOn = d18), habit("d", "18:00", deleted = true))),
        ).of(ReminderKind.HABIT)
        assertEquals(emptyList(), rs)
        val archivedTomorrow = planReminders(noon, input(habits = listOf(habit("a", "18:00", archivedOn = d19)))).of(ReminderKind.HABIT)
        assertEquals(setOf(d18), archivedTomorrow.map { it.date }.toSet())
    }

    @Test fun timedOpenTaskPingsUntimedNever() {
        val rs = planReminders(
            noon,
            input(tasks = listOf(TaskPlan("rent", d18, LocalTime.of(18, 30), done = false), TaskPlan("done", d18, LocalTime.of(18, 30), done = true))),
        ).of(ReminderKind.TASK)
        assertEquals(listOf(at(2026, 11, 18, 18, 20), at(2026, 11, 18, 18, 30)), rs.map { it.fireAt })
        assertTrue(rs.all { it.itemId == "rent" })
    }

    @Test fun seriesOccurrenceTomorrowIsPlanned() {
        val t = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val today = TaskPlan(instanceId(t, d18).toString(), d18, LocalTime.of(8, 30), done = false) // already a row, its time is past
        val rs = planReminders(noon, input(tasks = listOf(today), series = listOf(SeriesPlan(t, RepeatRule.Daily, LocalDate.of(2026, 11, 1), d18, LocalTime.of(8, 30)))))
            .of(ReminderKind.TASK)
        assertEquals(
            listOf("task:${instanceId(t, d19)}:2026-11-19:before", "task:${instanceId(t, d19)}:2026-11-19:at"),
            rs.map { it.key },
        )
    }

    @Test fun seriesRowThatAlreadyExistsIsNotPlannedTwice() {
        val t = UUID.fromString("00000000-0000-0000-0000-0000000000ab")
        val row = TaskPlan(instanceId(t, d19).toString(), d19, LocalTime.of(20, 0), done = true) // created and done early
        val rs = planReminders(noon, input(tasks = listOf(row), series = listOf(SeriesPlan(t, RepeatRule.Daily, d18, d19, LocalTime.of(20, 0)))))
            .of(ReminderKind.TASK)
        assertEquals(emptyList(), rs)
    }

    @Test fun stoppedSeriesYieldsNothing() {
        val t = UUID.randomUUID()
        val rs = planReminders(noon, input(series = listOf(SeriesPlan(t, RepeatRule.Daily, d18, LocalDate.of(9999, 12, 31), LocalTime.of(20, 0)))))
        assertEquals(emptyList(), rs.of(ReminderKind.TASK))
    }

    @Test fun journalAt21UnlessWritten() {
        val rs = planReminders(noon, input(journal = setOf(d18))).of(ReminderKind.JOURNAL)
        assertEquals(listOf(d19 to at(2026, 11, 19, 21)), rs.map { it.date to it.fireAt })
        assertEquals(listOf(Ping.AT), rs.map { it.ping })
    }

    @Test fun dayEndWarningOneHourBefore() {
        val rs = planReminders(noon, input()).of(ReminderKind.DAY_END)
        assertEquals(listOf(d18 to at(2026, 11, 19, 3), d19 to at(2026, 11, 20, 3)), rs.map { it.date to it.fireAt })
    }

    @Test fun tomorrowUsesThePendingDayEnd() {
        val pending = versions + DaySettingsVersion(at(2026, 11, 19, 4), DaySettings(ist, LocalTime.of(6, 0)), null)
        val rs = planReminders(noon, input(v = pending)).of(ReminderKind.DAY_END)
        assertEquals(listOf(d18 to at(2026, 11, 19, 3), d19 to at(2026, 11, 20, 5)), rs.map { it.date to it.fireAt })
    }

    @Test fun keysAreStable() {
        val i = input(habits = listOf(habit("gym", "18:00")))
        assertEquals(planReminders(noon, i), planReminders(noon, i))
        assertEquals("habit:gym:2026-11-18:before", planReminders(noon, i).first().key)
        assertEquals("habit:gym:2026-11-18:before", reminderKey(ReminderKind.HABIT, "gym", d18, Ping.BEFORE))
        assertEquals("journal:-:2026-11-18:at", reminderKey(ReminderKind.JOURNAL, null, d18, Ping.AT))
    }

    @Test fun planIsSortedByTime() {
        val rs = planReminders(noon, input(habits = listOf(habit("b", "19:00"), habit("a", "18:00")), journal = emptySet()))
        assertEquals(rs.sortedBy { it.fireAt }, rs)
    }

    @Test fun snoozePastDayEndIsNull() {
        assertNull(snoozeAt(at(2026, 11, 19, 3, 40), d18, versions))
        assertEquals(at(2026, 11, 18, 12, 30), snoozeAt(noon, d18, versions))
    }

    @Test fun quietHours() {
        assertFalse(isQuietHours(at(2026, 11, 18, 22, 59), ist))
        assertTrue(isQuietHours(at(2026, 11, 18, 23, 0), ist))
        assertTrue(isQuietHours(at(2026, 11, 19, 3, 0), ist))
        assertFalse(isQuietHours(at(2026, 11, 19, 7, 0), ist))
    }

    @Test fun replanAtIsJustAfterDayEnd() {
        assertEquals(at(2026, 11, 19, 4).plusSeconds(1), replanAt(noon, versions))
    }

    @Test fun weekReviewPingOnSunday20() {
        val sunNoon = at(2026, 11, 22, 12)
        val rs = planReminders(sunNoon, input()).of(ReminderKind.WEEK_REVIEW)
        assertEquals(listOf(LocalDate.of(2026, 11, 22) to at(2026, 11, 22, 20)), rs.map { it.date to it.fireAt })
        assertEquals("week_review:-:2026-11-22:at", rs.single().key)
        assertEquals(emptyList(), planReminders(noon, input()).of(ReminderKind.WEEK_REVIEW)) // wed + thu: none
    }

    // --- M9: the owner's reminder settings ---------------------------------------------------------------------

    @Test fun kindOffPlansNothing() {
        val rs = planReminders(noon, input(habits = listOf(habit("gym", "18:00")), journal = emptySet(), prefs = ReminderPrefs(habits = false)))
        assertEquals(emptyList(), rs.of(ReminderKind.HABIT))
        assertEquals(2, rs.of(ReminderKind.JOURNAL).size) // the others still ring
        val none = planReminders(noon, input(journal = emptySet(), prefs = ReminderPrefs(journal = false, dayEnd = false)))
        assertEquals(emptyList(), none.of(ReminderKind.JOURNAL) + none.of(ReminderKind.DAY_END))
    }

    @Test fun journalAtFromPrefs() {
        val rs = planReminders(noon, input(journal = emptySet(), prefs = ReminderPrefs(journalAt = LocalTime.of(22, 30)))).of(ReminderKind.JOURNAL)
        assertEquals(at(2026, 11, 18, 22, 30), rs.first().fireAt)
    }

    @Test fun dayEndLeadFromPrefs() {
        val rs = planReminders(noon, input(prefs = ReminderPrefs(dayEndLead = java.time.Duration.ofMinutes(30)))).of(ReminderKind.DAY_END)
        assertEquals(at(2026, 11, 19, 3, 30), rs.first().fireAt)
    }

    @Test fun weekReviewAtFromPrefs() {
        val rs = planReminders(at(2026, 11, 22, 12), input(prefs = ReminderPrefs(weekReviewAt = LocalTime.of(19, 0)))).of(ReminderKind.WEEK_REVIEW)
        assertEquals(at(2026, 11, 22, 19), rs.single().fireAt)
        assertEquals(emptyList(), planReminders(at(2026, 11, 22, 12), input(prefs = ReminderPrefs(weekReview = false))).of(ReminderKind.WEEK_REVIEW))
    }

    @Test fun prefsFromFieldsDefaultsAndBadValues() {
        assertEquals(ReminderPrefs(), reminderPrefs())
        assertEquals(
            ReminderPrefs(habits = false, journalAt = LocalTime.of(22, 30), dayEnd = false, dayEndLead = java.time.Duration.ofMinutes(120), weekReviewAt = LocalTime.of(19, 0)),
            reminderPrefs(habits = false, journalAt = "22:30", dayEnd = false, leadMinutes = 120, weekReviewAt = "19:00"),
        )
        assertEquals(DAY_END_LEAD, reminderPrefs(leadMinutes = 45).dayEndLead) // only 30/60/120
        assertEquals(JOURNAL_AT, reminderPrefs(journalAt = "25:99").journalAt)
    }
}
