package app.loopdo.shared.day

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsVersionsTest {
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    private val ny = DaySettings(ZoneId.of("America/New_York"), LocalTime.of(4, 0))
    private val utc = DaySettings(ZoneId.of("UTC"), LocalTime.of(4, 0))
    private fun at(s: String) = Instant.parse(s)
    private fun base(s: DaySettings) = DaySettingsVersion(Instant.EPOCH, s, null)

    @Test
    fun resolverUsesVersionInForceAtInstant() {
        val versions = listOf(
            base(ist),
            DaySettingsVersion(at("2026-11-14T22:30:00Z"), ny, LocalDate.of(2026, 11, 14)),
        )
        val dateAt = dateResolver(versions)
        assertEquals(LocalDate.of(2026, 11, 14), dateAt(at("2026-11-13T23:30:00Z"))) // Sat 05:00 IST
        assertEquals(LocalDate.of(2026, 11, 15), dateAt(at("2026-11-15T15:00:00Z"))) // Sun 10:00 NY
    }

    @Test
    fun resolverOrderOfListDoesNotMatter() {
        val v2 = DaySettingsVersion(at("2026-11-14T22:30:00Z"), ny, LocalDate.of(2026, 11, 14))
        val t = at("2026-11-15T15:00:00Z")
        assertEquals(dateResolver(listOf(base(ist), v2))(t), dateResolver(listOf(v2, base(ist)))(t))
    }

    @Test
    fun resolverAppliesFloor() {
        val akl = DaySettings(ZoneId.of("Pacific/Auckland"), LocalTime.MIDNIGHT)
        val la = DaySettings(ZoneId.of("America/Los_Angeles"), LocalTime.of(4, 0))
        val from = at("2026-11-16T00:00:00Z")
        val versions = listOf(base(akl), nextVersion(listOf(base(akl)), la, from))
        val dateAt = dateResolver(versions)
        val state = scheduleChange(DaySettingsState(akl), la, from)
        var t = from
        repeat(96 * 4) {
            assertEquals(currentLoopDoDate(state, t), dateAt(t), "at $t")
            t = t.plus(Duration.ofMinutes(15))
        }
    }

    @Test
    fun nextVersionMatchesScheduleChange() {
        val now = at("2026-11-16T22:30:00Z")
        val v = nextVersion(listOf(base(ist)), ny, now)
        val st = scheduleChange(DaySettingsState(ist), ny, now)
        assertEquals(DaySettingsVersion(st.pendingEffectiveAt!!, ny, st.pendingFloorDate), v)
    }

    @Test
    fun pendingVersionIsReplaced() {
        val now = at("2026-11-16T22:30:00Z")
        val pending = nextVersion(listOf(base(ist)), ny, now) // takes effect later than now
        val replaced = nextVersion(listOf(base(ist), pending), utc, now.plusSeconds(60))
        assertEquals(nextVersion(listOf(base(ist)), utc, now.plusSeconds(60)), replaced)
    }

    @Test
    fun dayEndAtFollowsTheHistory() {
        val versions = listOf(base(ist), DaySettingsVersion(at("2026-11-14T22:30:00Z"), ny, LocalDate.of(2026, 11, 14)))
        // Sat 14 (IST) ends Sun 04:00 IST, exactly when the New York version takes over.
        assertEquals(at("2026-11-14T22:30:00Z"), dayEndAt(versions, at("2026-11-14T19:12:00Z")))
        // Sun 15 (NY) ends Mon 04:00 New York time.
        assertEquals(at("2026-11-16T09:00:00Z"), dayEndAt(versions, at("2026-11-15T15:00:00Z")))
    }
}
