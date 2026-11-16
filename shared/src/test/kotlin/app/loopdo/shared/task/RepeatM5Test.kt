package app.loopdo.shared.task

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M5: the stored form of a repeat rule, and which occurrences still need a task. */
class RepeatM5Test {
    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    @Test
    fun roundTripsAllFourShapes() {
        val rules = listOf(
            RepeatRule.Daily,
            RepeatRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            RepeatRule.MonthlyOnDay(1),
            RepeatRule.EveryNDays(3, d(16)),
        )
        rules.forEach { assertEquals(it, parseRepeatRule(it.toJson())) }
        assertEquals("""{"type":"weekly","days":[1,3,5]}""", rules[1].toJson())
        assertEquals("""{"type":"every_n_days","n":3,"anchor":"2026-11-16"}""", rules[3].toJson())
    }

    @Test
    fun rejectsUnknownOrBrokenJson() {
        assertNull(parseRepeatRule("""{"type":"yearly"}"""))
        assertNull(parseRepeatRule("""{"type":"monthly","day":40}"""))
        assertNull(parseRepeatRule("not json"))
    }

    @Test
    fun instancesToCreateIsIdempotent() {
        val monthly = RepeatRule.MonthlyOnDay(1)
        assertEquals(listOf(d(1)), instancesToCreate(monthly, start = d(1), generatedThrough = null, today = d(16)))
        assertEquals(emptyList(), instancesToCreate(monthly, start = d(1), generatedThrough = d(16), today = d(16)))
        val mondays = RepeatRule.Weekly(setOf(DayOfWeek.MONDAY))
        assertEquals(listOf(d(2), d(9), d(16)), instancesToCreate(mondays, start = d(1), generatedThrough = null, today = d(16)))
        assertEquals(listOf(d(16)), instancesToCreate(mondays, start = d(1), generatedThrough = d(15), today = d(16)))
    }
}
