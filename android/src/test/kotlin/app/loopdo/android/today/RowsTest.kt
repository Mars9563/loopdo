package app.loopdo.android.today

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RowsTest {
    @Test
    fun parsesPostgresAndIsoTimestamps() {
        val t = Instant.parse("2026-11-15T13:12:00Z")
        assertEquals(t, parseInstant("2026-11-15 13:12:00+00"))
        assertEquals(t, parseInstant("2026-11-15 18:42:00+05:30"))
        assertEquals(t, parseInstant("2026-11-15T13:12:00.000Z"))
        assertEquals(Instant.EPOCH, parseInstant("1970-01-01 00:00:00+00"))
    }

    @Test
    fun parsesWeekdaysInBothArrayShapes() {
        assertEquals(setOf(MONDAY, WEDNESDAY, FRIDAY), parseWeekdays("[1,3,5]"))
        assertEquals(setOf(MONDAY, WEDNESDAY, FRIDAY), parseWeekdays("{1,3,5}"))
        assertEquals(emptySet(), parseWeekdays(null))
    }
}
