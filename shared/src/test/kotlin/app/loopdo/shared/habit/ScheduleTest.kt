package app.loopdo.shared.habit

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleTest {
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private val mwf = HabitSchedule(d(11, 2), Recurrence.WEEKDAYS, setOf(MONDAY, WEDNESDAY, FRIDAY))
    private val daily = HabitSchedule(d(11, 16), Recurrence.DAILY)

    @Test fun dueOnScheduledWeekday() = assertTrue(isDue(listOf(mwf), d(11, 13))) // Fri

    @Test fun notDueOffDay() = assertFalse(isDue(listOf(mwf), d(11, 14))) // Sat

    @Test fun notDueBeforeFirstVersion() = assertFalse(isDue(listOf(mwf), d(11, 1)))

    @Test
    fun newVersionAppliesFromItsDateOnly() {
        val both = listOf(mwf, daily)
        assertFalse(isDue(both, d(11, 14))) // Sat: old schedule still applies
        assertTrue(isDue(both, d(11, 17))) // Tue: daily now
    }

    @Test
    fun versionOrderInListDoesNotMatter() {
        assertEquals(daily, scheduleOn(listOf(daily, mwf), d(11, 20)))
        assertEquals(mwf, scheduleOn(listOf(daily, mwf), d(11, 15)))
    }

    @Test fun noVersionBeforeStart() = assertNull(scheduleOn(listOf(mwf), d(11, 1)))

    @Test fun editsStartTomorrow() = assertEquals(d(11, 15), nextVersionValidFrom(d(11, 14)))

    @Test
    fun weekdaysMustNotBeEmpty() {
        assertFailsWith<IllegalArgumentException> { HabitSchedule(d(11, 2), Recurrence.WEEKDAYS) }
    }

    @Test
    fun dailyMustHaveNoWeekdays() {
        assertFailsWith<IllegalArgumentException> { HabitSchedule(d(11, 2), Recurrence.DAILY, setOf(MONDAY)) }
    }
}
