package app.loopdo.shared.habit

import app.loopdo.shared.habit.DayOutcome.MISS
import app.loopdo.shared.habit.DayOutcome.NEUTRAL
import app.loopdo.shared.habit.DayOutcome.PENDING
import app.loopdo.shared.habit.DayOutcome.SUCCESS
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomesTest {
    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    private fun yesNo(id: String, from: LocalDate, archivedOn: LocalDate? = null) =
        Habit(id, HabitKind.YES_NO, 1, listOf(HabitSchedule(from, Recurrence.DAILY)), archivedOn)

    private val water = Habit("water", HabitKind.COUNT, 8, listOf(HabitSchedule(d(10), Recurrence.DAILY)))
    private val gym = Habit(
        "gym", HabitKind.YES_NO, 1,
        listOf(HabitSchedule(d(9), Recurrence.WEEKDAYS, setOf(MONDAY, WEDNESDAY, FRIDAY))),
    )

    @Test
    fun countHabitPartialIsMiss() {
        val out = habitOutcomes(water, listOf(HabitLog(d(10), 5), HabitLog(d(11), 8)), today = d(12)).toMap()
        assertEquals(MISS, out[d(10)])
        assertEquals(SUCCESS, out[d(11)])
    }

    @Test
    fun todayNotDoneIsPending() =
        assertEquals(PENDING, habitOutcomes(water, listOf(HabitLog(d(12), 3)), today = d(12)).toMap()[d(12)])

    @Test
    fun todayDoneIsSuccess() =
        assertEquals(SUCCESS, habitOutcomes(water, listOf(HabitLog(d(12), 8)), today = d(12)).toMap()[d(12)])

    @Test
    fun habitCreatedTodayIsOnlyPending() =
        assertEquals(listOf(d(14) to PENDING), habitOutcomes(yesNo("new", d(14)), emptyList(), today = d(14)))

    @Test
    fun offDaysAreNeutral() {
        // Mon 9 .. Sun 15: gym due Mon/Wed/Fri only.
        val out = habitOutcomes(gym, listOf(HabitLog(d(9), 1), HabitLog(d(11), 1)), today = d(15)).toMap()
        assertEquals(listOf(SUCCESS, NEUTRAL, SUCCESS, NEUTRAL, MISS, NEUTRAL, NEUTRAL), (9..15).map { out[d(it)] })
    }

    @Test
    fun recordsAreAscendingAndContiguous() {
        val out = habitOutcomes(water, emptyList(), today = d(14))
        assertEquals((10..14).map { d(it) }, out.map { it.first })
    }

    @Test
    fun archivedStopsRecords() {
        val h = yesNo("old", d(10), archivedOn = d(12))
        assertEquals(listOf(d(10), d(11)), habitOutcomes(h, emptyList(), today = d(14)).map { it.first })
    }

    @Test
    fun perfectDayNeutralWhenNothingDue() {
        val out = perfectDayOutcomes(listOf(gym), emptyMap(), start = d(14), today = d(15)).toMap()
        assertEquals(NEUTRAL, out[d(14)]) // Saturday
    }

    @Test
    fun perfectDayMissWhenOneDueHabitMissed() {
        val logs = mapOf("water" to listOf(HabitLog(d(13), 8)), "gym" to emptyList())
        val out = perfectDayOutcomes(listOf(water, gym), logs, start = d(13), today = d(14)).toMap()
        assertEquals(MISS, out[d(13)]) // Fri: water done, gym not
    }

    @Test
    fun perfectDaySuccessWhenAllDueDone() {
        val logs = mapOf("water" to listOf(HabitLog(d(13), 8)), "gym" to listOf(HabitLog(d(13), 1)))
        val out = perfectDayOutcomes(listOf(water, gym), logs, start = d(13), today = d(14)).toMap()
        assertEquals(SUCCESS, out[d(13)])
    }

    @Test
    fun perfectDayTodayIncompleteIsPending() {
        val out = perfectDayOutcomes(listOf(water), emptyMap(), start = d(14), today = d(14)).toMap()
        assertEquals(PENDING, out[d(14)])
    }

    @Test
    fun journalPhotoOnlyDayCounts() {
        // The caller puts a photo-only day in entryDays; the rule is "text or photo".
        val out = journalOutcomes(setOf(d(12)), start = d(11), today = d(13)).toMap()
        assertEquals(MISS, out[d(11)])
        assertEquals(SUCCESS, out[d(12)])
        assertEquals(PENDING, out[d(13)])
    }

    @Test
    fun activeDayFromAnyActivity() {
        val out = activeDayOutcomes(setOf(d(11), d(13)), start = d(11), today = d(13)).toMap()
        assertEquals(listOf(SUCCESS, MISS, SUCCESS), (11..13).map { out[d(it)] })
    }

    @Test
    fun startAfterTodayIsEmpty() = assertEquals(emptyList(), activeDayOutcomes(emptySet(), start = d(15), today = d(14)))
}
