package app.loopdo.shared.habit

import app.loopdo.shared.streak.computeStreak
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** M5: per-version targets, pause, delete-as-hide (design 2026-11-16). */
class ScheduleM5Test {
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private val daily = HabitSchedule(d(1), Recurrence.DAILY)

    @Test
    fun targetOnUsesTheVersionOfThatDay() {
        val h = Habit("vocab", HabitKind.COUNT, 35, listOf(daily, HabitSchedule(d(17), Recurrence.DAILY, target = 50)))
        assertEquals(35, targetOn(h, d(16)))
        assertEquals(50, targetOn(h, d(17)))
        val noTargetVersion = h.copy(schedules = listOf(daily, HabitSchedule(d(17), Recurrence.WEEKDAYS, setOf(java.time.DayOfWeek.MONDAY))))
        assertEquals(35, targetOn(noTargetVersion, d(17)))
    }

    @Test
    fun outcomesJudgeEachDayByItsOwnTarget() {
        // 40 words on the 16th meets 35; the same 40 on the 17th misses the new 50.
        val h = Habit("vocab", HabitKind.COUNT, 35, listOf(daily, HabitSchedule(d(17), Recurrence.DAILY, target = 50)))
        val logs = listOf(HabitLog(d(16), 40), HabitLog(d(17), 40))
        val byDay = habitOutcomes(h, logs, d(18)).toMap()
        assertEquals(DayOutcome.SUCCESS, byDay[d(16)])
        assertEquals(DayOutcome.MISS, byDay[d(17)])
    }

    @Test
    fun pausedDaysAreNeutral() {
        val h = Habit("gym", HabitKind.YES_NO, 1, listOf(daily) + pauseVersions(daily, d(18), d(20)))
        val logs = listOf(15, 16, 17, 21, 22).map { HabitLog(d(it), 1) }
        val byDay = habitOutcomes(h, logs, d(22)).toMap()
        (18..20).forEach { assertEquals(DayOutcome.NEUTRAL, byDay[d(it)], "day $it") }
        assertEquals(5, computeStreak(habitOutcomes(h, logs, d(22))).current) // the streak waited through the pause
    }

    @Test
    fun pauseVersionsKeepLaterEdits() {
        val current = HabitSchedule(d(17), Recurrence.DAILY, target = 50)
        assertEquals(
            listOf(HabitSchedule(d(18), Recurrence.PAUSED), HabitSchedule(d(21), Recurrence.DAILY, target = 50)),
            pauseVersions(current, d(18), d(20)),
        )
    }

    @Test
    fun hiddenHabitStillCountsInPerfectDays() {
        // Hiding ("delete forever") is display-only: the rule still sees the missed habit, so the day stays a miss.
        val water = Habit("water", HabitKind.YES_NO, 1, listOf(daily))
        val gym = Habit("gym", HabitKind.YES_NO, 1, listOf(daily))
        val logs = mapOf("water" to listOf(HabitLog(d(16), 1)), "gym" to emptyList())
        assertEquals(DayOutcome.MISS, perfectDayOutcomes(listOf(water, gym), logs, d(16), d(17)).toMap()[d(16)])
    }
}
