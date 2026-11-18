package app.loopdo.shared.stats

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.loopDoDate
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusTest {
    private val mon = LocalDate.of(2026, 11, 23) // week 48: mon 23 – sun 29 nov
    private fun d(n: Int) = mon.plusDays(n.toLong())
    private val mwfs = listOf(HabitSchedule(LocalDate.of(2026, 11, 1), Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)))
    private val gym = Habit("gym", HabitKind.YES_NO, 1, mwfs)
    private fun focusHabit(goal: Int) = Focus("f1", mon, FocusKind.HABIT, "gym", "gym", goal, null)
    private fun focusTask(deadline: LocalDate) = Focus("f2", mon, FocusKind.TASK, "t", "pay rent", null, deadline)
    private fun input(logs: List<HabitLog> = emptyList(), tasks: List<StatTask> = emptyList()) =
        WeekInput(listOf(StatHabit(gym, "gym", 0)), mapOf("gym" to logs), tasks, emptySet())

    @Test fun windowIsSundayAndMonday() {
        assertTrue(focusWindowOpen(mon, mon.minusDays(1)))
        assertTrue(focusWindowOpen(mon, mon))
        assertFalse(focusWindowOpen(mon, mon.minusDays(2)))
        assertFalse(focusWindowOpen(mon, d(1)))
    }

    @Test fun windowUsesLoopDoDates() {
        val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
        val sunday2am = LocalDateTime.of(2026, 11, 22, 2, 0).atZone(ist.zone).toInstant()
        val today = loopDoDate(sunday2am, ist)
        assertEquals(LocalDate.of(2026, 11, 21), today) // still saturday's LoopDo date
        assertNull(focusWeekFor(today))
        assertFalse(focusWindowOpen(mon, today))
    }

    @Test fun weeksForEachDay() {
        assertEquals(mon, focusWeekFor(mon.minusDays(1)))
        assertEquals(mon, focusWeekFor(mon))
        assertNull(focusWeekFor(d(1)))
        assertEquals(mon.minusDays(7), reviewWeekFor(mon))           // monday: the week that just ended
        assertEquals(mon.minusDays(7), reviewWeekFor(mon.minusDays(1))) // sunday: its own week
        assertEquals(mon, reviewWeekFor(d(2)))                         // wednesday: this week
    }

    @Test fun defaultGoalIsDueDays() {
        assertEquals(4, defaultGoal(gym, mon))
        assertEquals(7, defaultGoal(Habit("d", HabitKind.YES_NO, 1, listOf(HabitSchedule(LocalDate.of(2026, 11, 1), Recurrence.DAILY))), mon))
        assertEquals(1, defaultGoal(Habit("p", HabitKind.YES_NO, 1, listOf(HabitSchedule(LocalDate.of(2026, 11, 1), Recurrence.PAUSED))), mon))
    }

    @Test fun taskDeadlineInsideOrSunday() {
        assertEquals(d(3), taskDeadline(d(3), mon))
        assertEquals(d(6), taskDeadline(mon.minusDays(2), mon))
        assertEquals(d(6), taskDeadline(d(9), mon))
        assertEquals(d(6), taskDeadline(null, mon))
    }

    @Test fun habitFocusKeptOrMissedBy() {
        val logs = listOf(HabitLog(d(0), 1), HabitLog(d(2), 1), HabitLog(d(4), 1))
        val r = focusResult(focusHabit(4), input(logs), d(7))
        assertEquals(FocusResult.Habit(3, 4), r)
        assertFalse(r.kept())
        assertEquals("gym 4×  →  3/4 · missed by 1", focusLine(focusHabit(4), r, d(7)))
        assertEquals("gym 3×  →  3/3 ✓", focusLine(focusHabit(3), focusResult(focusHabit(3), input(logs), d(7)), d(7)))
    }

    @Test fun habitFocusMidweekSaysSoFar() {
        val r = focusResult(focusHabit(4), input(listOf(HabitLog(d(0), 1))), d(2))
        assertEquals("gym 4×  →  1/4 so far", focusLine(focusHabit(4), r, d(2)))
    }

    @Test fun movedTaskKeepsSavedDeadline() {
        val moved = StatTask("t", "pay rent", due = d(4), completedOn = d(3), lateSince = null) // due fri now, done thu
        val f = focusTask(d(2)) // the promise said wed
        val r = focusResult(f, input(tasks = listOf(moved)), d(7))
        assertFalse(r.kept())
        assertEquals("pay rent  →  done thu · late", focusLine(f, r, d(7)))
    }

    @Test fun taskDoneOnTime() {
        val f = focusTask(d(2))
        val r = focusResult(f, input(tasks = listOf(StatTask("t", "pay rent", d(2), d(1), null))), d(7))
        assertTrue(r.kept())
        assertEquals("pay rent  →  done tue ✓", focusLine(f, r, d(7)))
        val open = focusResult(f, input(tasks = listOf(StatTask("t", "pay rent", d(2), null, null))), d(7))
        assertEquals("pay rent  →  not done", focusLine(f, open, d(7)))
        assertEquals("pay rent  →  due wed", focusLine(f, open, d(1)))
    }

    @Test fun sundayVerdictWhenTheGoalCanNoLongerBeReached() {
        // gym is due mon/wed/fri/sat; done mon, wed, fri; missed sat. On sunday (not a gym day) 4 can't be reached.
        val logs = listOf(HabitLog(d(0), 1), HabitLog(d(2), 1), HabitLog(d(4), 1))
        val r = focusResult(focusHabit(4), input(logs), d(6))
        assertEquals("gym 4×  →  3/4 · missed by 1", focusLine(focusHabit(4), r, d(6)))
        // On wednesday, with fri and sat still to come, it can: "so far".
        val mid = focusResult(focusHabit(4), input(listOf(HabitLog(d(0), 1))), d(2))
        assertEquals("gym 4×  →  1/4 so far", focusLine(focusHabit(4), mid, d(2)))
    }

    @Test fun focusOnAHiddenHabitStillCounts() {
        val hidden = WeekInput(listOf(StatHabit(gym, "gym", 0, hidden = true)), mapOf("gym" to (0..6).map { HabitLog(d(it), 1) }), emptyList(), emptySet())
        assertTrue(focusResult(focusHabit(4), hidden, d(7)).kept())
    }
}
