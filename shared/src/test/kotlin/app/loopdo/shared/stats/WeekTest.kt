package app.loopdo.shared.stats

import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeekTest {
    private val mon = LocalDate.of(2026, 11, 16) // week 47: mon 16 – sun 22 nov
    private fun d(n: Int) = mon.plusDays(n.toLong()) // 0 = mon … 6 = sun
    private val nextMon = d(7)
    private val daily = listOf(HabitSchedule(LocalDate.of(2026, 11, 1), Recurrence.DAILY))
    private fun habit(id: String, kind: HabitKind = HabitKind.YES_NO, target: Int = 1, schedules: List<HabitSchedule> = daily, archivedOn: LocalDate? = null, order: Int = 0) =
        StatHabit(Habit(id, kind, target, schedules, archivedOn), id, order)
    private fun ticks(vararg days: Int, count: Int = 1) = days.map { HabitLog(d(it), count) }
    private fun input(habits: List<StatHabit>, logs: Map<String, List<HabitLog>> = emptyMap(), tasks: List<StatTask> = emptyList(), active: Set<LocalDate> = emptySet()) =
        WeekInput(habits, logs, tasks, active)
    private fun cells(s: String) = s.map { when (it) { 'D' -> Cell.DONE; 'M' -> Cell.MISSED; 'F' -> Cell.FROZEN; '.' -> Cell.NOT_DUE; 'o' -> Cell.OPEN; else -> Cell.FUTURE } }
    /** Ticked every day 1–15 nov, so a freeze is in the bank by week 47. */
    private fun earlyRun() = (1..15).map { HabitLog(LocalDate.of(2026, 11, it), 1) }

    @Test fun weekStartAndIsoNumber() {
        assertEquals(mon, weekStart(d(6)))
        assertEquals(mon, weekStart(mon))
        assertEquals(47, isoWeek(d(6)))
    }

    @Test fun dailyHabitCountsDueDaysDone() {
        val w = weekReview(input(listOf(habit("meditate")), mapOf("meditate" to ticks(0, 1, 2, 3, 4))), mon, nextMon)
        val h = w.habits.single()
        assertEquals(cells("DDDDDMM"), h.cells)
        assertEquals(5 to 7, h.done to h.due)
        assertEquals(5 to 7, w.done to w.total)
        assertEquals(71, w.percent)
    }

    @Test fun countHabitNeedsItsTarget() {
        val logs = mapOf("water" to listOf(HabitLog(d(0), 7), HabitLog(d(1), 8)))
        val w = weekReview(input(listOf(habit("water", HabitKind.COUNT, 8)), logs), mon, nextMon)
        assertEquals(cells("MDMMMMM"), w.habits.single().cells)
        assertEquals(1, w.done)
    }

    @Test fun todayOpenIsNotCounted() {
        val w = weekReview(input(listOf(habit("meditate")), mapOf("meditate" to ticks(0, 1, 2, 3, 4, 5))), mon, d(6))
        assertEquals(cells("DDDDDDo"), w.habits.single().cells)
        assertEquals(6 to 6, w.done to w.total)
        val midweek = weekReview(input(listOf(habit("meditate")), mapOf("meditate" to ticks(0, 1))), mon, d(2))
        assertEquals(cells("DDo____"), midweek.habits.single().cells)
    }

    @Test fun weekdayHabitNotDueDays() {
        val mwf = listOf(HabitSchedule(LocalDate.of(2026, 11, 1), Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))
        val h = weekReview(input(listOf(habit("gym", schedules = mwf)), mapOf("gym" to ticks(0, 4))), mon, nextMon).habits.single()
        assertEquals(cells("D.M.D.."), h.cells)
        assertEquals(2 to 3, h.done to h.due)
    }

    @Test fun archivedMidWeekStopsCounting() {
        val h = weekReview(input(listOf(habit("old", archivedOn = d(2))), mapOf("old" to ticks(0))), mon, nextMon).habits.single()
        assertEquals(cells("DM....."), h.cells)
        assertEquals(1 to 2, h.done to h.due)
    }

    @Test fun frozenDayIsMissedButMarked() {
        val logs = mapOf("meditate" to earlyRun() + ticks(0, 2, 3, 4, 5, 6))
        val w = weekReview(input(listOf(habit("meditate")), logs), mon, nextMon)
        assertEquals(cells("DFDDDDD"), w.habits.single().cells)
        assertEquals(6 to 7, w.done to w.total) // a freeze protects the streak, not the week's numbers (owner)
    }

    @Test fun tasksOnTimeAndLate() {
        val tasks = listOf(
            StatTask("a", "on time", due = d(1), completedOn = d(1), lateSince = null),
            StatTask("b", "done late", due = d(2), completedOn = d(4), lateSince = d(3)),
            StatTask("c", "due today", due = d(5), completedOn = null, lateSince = null),
            StatTask("e", "still open", due = d(0), completedOn = null, lateSince = d(1)),
        )
        val w = weekReview(input(emptyList(), tasks = tasks), mon, d(5)) // saturday
        assertEquals(1 to 3, w.done to w.total) // a, b, e count; c is due today and still open
        assertEquals(2, w.tasksDone) // a and b were completed this week
        assertEquals(listOf(LateTask("still open", 5)), w.late)
    }

    @Test fun deltaVsPreviousWeek() {
        val logs = mapOf("m" to (0 until 7).map { HabitLog(mon.minusDays(7).plusDays(it.toLong()), 1) }.take(6) + ticks(0, 1, 2, 3, 4, 5, 6))
        val w = weekReview(input(listOf(habit("m")), logs), mon, nextMon)
        assertEquals(100, w.percent)
        assertEquals(100 - 86, w.deltaPoints)
        val first = weekReview(input(listOf(habit("m", schedules = listOf(HabitSchedule(mon, Recurrence.DAILY))))), mon, nextMon)
        assertNull(first.deltaPoints)
    }

    @Test fun bestAndSlipped() {
        val mwfs = listOf(HabitSchedule(LocalDate.of(2026, 11, 1), Recurrence.WEEKDAYS, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)))
        val habits = listOf(habit("kanji", order = 1), habit("gym", schedules = mwfs, order = 2), habit("water", order = 3))
        val logs = mapOf("kanji" to ticks(0, 1, 2, 3, 4, 5, 6), "gym" to ticks(0, 4, 5), "water" to ticks(0, 1, 2, 3, 4, 5))
        val tasks = listOf(StatTask("t", "fix bike brake", due = d(3), completedOn = null, lateSince = d(4)))
        val w = weekReview(input(habits, logs, tasks), mon, nextMon)
        assertEquals("kanji", w.best!!.title)
        assertEquals(listOf("gym 3/4", "water 6/7"), w.slipped)
        val onlyGym = weekReview(input(habits.take(2), logs, tasks), mon, nextMon)
        assertEquals(listOf("gym 3/4", "fix bike brake late"), onlyGym.slipped)
    }

    @Test fun perfectAndActiveDays() {
        val habits = listOf(habit("a"), habit("b"))
        val logs = mapOf("a" to ticks(0, 1, 2), "b" to ticks(0, 2))
        val w = weekReview(input(habits, logs, active = setOf(d(0), d(1), d(2), d(5))), mon, nextMon)
        assertEquals(2, w.perfectDays) // mon, wed
        assertEquals(4, w.activeDays)
    }
}
