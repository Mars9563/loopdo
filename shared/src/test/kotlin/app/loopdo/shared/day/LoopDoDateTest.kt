package app.loopdo.shared.day

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoopDoDateTest {
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    private val ny = ZoneId.of("America/New_York")
    private val ny0230 = DaySettings(ny, LocalTime.of(2, 30))
    private val ny0130 = DaySettings(ny, LocalTime.of(1, 30))

    private fun at(s: String) = Instant.parse(s)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun stillSaturdayBeforeDayEnd() = // 00:42 IST, Sun 15 Nov
        assertEquals(d(2026, 11, 14), loopDoDate(at("2026-11-14T19:12:00Z"), ist))

    @Test
    fun newDayExactlyAtDayEnd() = // 04:00 IST, Sun 15 Nov
        assertEquals(d(2026, 11, 15), loopDoDate(at("2026-11-14T22:30:00Z"), ist))

    @Test
    fun dayEndInstantIst() =
        assertEquals(at("2026-11-14T22:30:00Z"), dayEndInstant(d(2026, 11, 14), ist))

    @Test
    fun midnightDayEndIsCalendarDate() =
        assertEquals(
            d(2026, 11, 15),
            loopDoDate(at("2026-11-14T19:12:00Z"), DaySettings(ist.zone, LocalTime.MIDNIGHT)),
        )

    @Test
    fun rejectsDayEndAfter8() {
        assertFailsWith<IllegalArgumentException> { DaySettings(ist.zone, LocalTime.of(8, 1)) }
    }

    @Test
    fun accepts8Exactly() {
        DaySettings(ist.zone, LocalTime.of(8, 0))
    }

    @Test
    fun defaultIsFourAm() = assertEquals(LocalTime.of(4, 0), DaySettings.default(ist.zone).dayEnd)

    // New York, day end 02:30; spring-forward gap 2026-03-08 02:00 EST -> 03:00 EDT.
    @Test
    fun gapDayEndIsTransitionInstant() =
        assertEquals(at("2026-03-08T07:00:00Z"), dayEndInstant(d(2026, 3, 7), ny0230))

    @Test
    fun gapBeforeTransitionIsPreviousDay() =
        assertEquals(d(2026, 3, 7), loopDoDate(at("2026-03-08T06:59:00Z"), ny0230))

    @Test
    fun gapAtTransitionIsNewDay() =
        assertEquals(d(2026, 3, 8), loopDoDate(at("2026-03-08T07:00:00Z"), ny0230))

    // New York, day end 01:30; fall-back overlap 2026-11-01 02:00 EDT -> 01:00 EST.
    @Test
    fun overlapUsesFirstOccurrence() =
        assertEquals(at("2026-11-01T05:30:00Z"), dayEndInstant(d(2026, 10, 31), ny0130))

    @Test
    fun overlapNeverGoesBackwards() {
        assertEquals(d(2026, 10, 31), loopDoDate(at("2026-11-01T05:29:00Z"), ny0130))
        assertEquals(d(2026, 11, 1), loopDoDate(at("2026-11-01T05:30:00Z"), ny0130))
        // 01:00 EST: the wall clock repeats, the LoopDo date must not.
        assertEquals(d(2026, 11, 1), loopDoDate(at("2026-11-01T06:00:00Z"), ny0130))
    }
}
