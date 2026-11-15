package app.loopdo.shared.day

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingSettingsTest {
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    private val ny = DaySettings(ZoneId.of("America/New_York"), LocalTime.of(4, 0))
    private val now = Instant.parse("2026-11-16T22:30:00Z") // Tue 17 Nov 04:00 IST

    @Test
    fun waitsForLaterOfBothDayEnds() {
        val st = scheduleChange(DaySettingsState(ist), ny, now)
        assertEquals(Instant.parse("2026-11-17T22:30:00Z"), st.pendingEffectiveAt)
        assertEquals(ny, st.pending)
        assertEquals(ist, st.current)
    }

    @Test
    fun dateNeverDecreasesAcrossSwitch() {
        val st = scheduleChange(DaySettingsState(ist), ny, now)
        val t = st.pendingEffectiveAt!!
        val before = currentLoopDoDate(st, t.minusSeconds(1))
        val after = currentLoopDoDate(st, t)
        assertTrue(after >= before, "date went from $before to $after")
    }

    @Test
    fun eastwardJumpNeverDecreases() {
        val st = scheduleChange(DaySettingsState(ny), ist, now)
        var previous = currentLoopDoDate(st, now)
        var t = now
        repeat(96) { // every 30 minutes for 48 hours
            t = t.plus(Duration.ofMinutes(30))
            val date = currentLoopDoDate(st, t)
            assertTrue(date >= previous, "date went from $previous to $date at $t")
            previous = date
        }
    }

    @Test
    fun effectiveBeforeAndAfter() {
        val st = scheduleChange(DaySettingsState(ist), ny, now)
        assertEquals(ist, effectiveSettings(st, now))
        val t = st.pendingEffectiveAt!!
        assertEquals(ist, effectiveSettings(st, t.minusSeconds(1)))
        assertEquals(ny, effectiveSettings(st, t))
    }

    @Test
    fun dueChangeFoldsBeforeNextSchedule() {
        val utc = DaySettings(ZoneId.of("UTC"), LocalTime.of(4, 0))
        val first = scheduleChange(DaySettingsState(ist), ny, now)
        val later = first.pendingEffectiveAt!!.plusSeconds(60)
        val second = scheduleChange(first, utc, later)
        assertEquals(ny, second.current)
        assertEquals(utc, second.pending)
    }

    @Test
    fun noPendingMeansCurrent() {
        val st = DaySettingsState(ist)
        assertEquals(ist, effectiveSettings(st, now))
        assertNull(st.pending)
    }

    private fun assertNeverDecreases(st: DaySettingsState, from: Instant, hours: Long) {
        var previous = currentLoopDoDate(st, from)
        var t = from
        repeat((hours * 4).toInt()) { // every 15 minutes
            t = t.plus(Duration.ofMinutes(15))
            val date = currentLoopDoDate(st, t)
            assertTrue(date >= previous, "date went from $previous to $date at $t")
            previous = date
        }
    }

    // Reviewer finding 1: new settings lagging the old by more than 24 h.
    @Test
    fun largeWestwardLagNeverReopens() {
        val akl = DaySettings(ZoneId.of("Pacific/Auckland"), LocalTime.MIDNIGHT)
        val la = DaySettings(ZoneId.of("America/Los_Angeles"), LocalTime.of(4, 0))
        val from = Instant.parse("2026-11-16T00:00:00Z")
        assertNeverDecreases(scheduleChange(DaySettingsState(akl), la, from), from, 96)
    }

    @Test
    fun zoneOnlyLagOverADayNeverReopens() {
        val kiritimati = DaySettings(ZoneId.of("Pacific/Kiritimati"), LocalTime.of(4, 0))
        val niue = DaySettings(ZoneId.of("Pacific/Niue"), LocalTime.of(4, 0))
        val from = Instant.parse("2026-11-16T00:00:00Z")
        assertNeverDecreases(scheduleChange(DaySettingsState(kiritimati), niue, from), from, 96)
    }

    @Test
    fun dayStretchesAtTheSwitch() {
        val akl = DaySettings(ZoneId.of("Pacific/Auckland"), LocalTime.MIDNIGHT)
        val la = DaySettings(ZoneId.of("America/Los_Angeles"), LocalTime.of(4, 0))
        val st = scheduleChange(DaySettingsState(akl), la, Instant.parse("2026-11-16T00:00:00Z"))
        val t = st.pendingEffectiveAt!!
        assertEquals(currentLoopDoDate(st, t.minusSeconds(1)), currentLoopDoDate(st, t))
    }

    @Test
    fun floorSurvivesTheNextChange() {
        val akl = DaySettings(ZoneId.of("Pacific/Auckland"), LocalTime.MIDNIGHT)
        val la = DaySettings(ZoneId.of("America/Los_Angeles"), LocalTime.of(4, 0))
        val from = Instant.parse("2026-11-16T00:00:00Z")
        val first = scheduleChange(DaySettingsState(akl), la, from)
        val afterSwitch = first.pendingEffectiveAt!!.plusSeconds(60)
        val second = scheduleChange(first, la.copy(dayEnd = LocalTime.of(5, 0)), afterSwitch)
        assertEquals(currentLoopDoDate(first, afterSwitch), currentLoopDoDate(second, afterSwitch))
        assertNeverDecreases(second, afterSwitch, 72)
    }
}
