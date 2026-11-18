package app.loopdo.desktop

import app.loopdo.client.time.AnchorStore
import app.loopdo.client.time.SignedAnchor
import app.loopdo.client.time.TrustedClock
import app.loopdo.desktop.time.LinuxDeviceClocks
import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.loopDoDate
import app.loopdo.shared.time.TimeAnchor
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D2 exit: a laptop clock set a day ahead can't move the LoopDo day; a new boot makes ticks unverified, never wrong. */
class ClockCheckTest {
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    private val server = Instant.parse("2026-11-18T20:00:00Z") // 01:30 IST on the 19th → LoopDo day 18 nov
    private fun store(a: SignedAnchor?) = object : AnchorStore { override fun current() = a; override fun save(a: SignedAnchor) {} }

    private fun clocks(wallAhead: Duration, uptimeS: String, boot: String) = object : app.loopdo.client.time.DeviceClocks by LinuxDeviceClocks({ uptimeS }, { boot }) {
        override fun wallClock(): Instant = server.plus(Duration.ofMinutes(10)).plus(wallAhead)
    }

    @Test
    fun `a wall clock a day ahead still gives the real day`() {
        val anchor = SignedAnchor(TimeAnchor(server, 1_000_000L, LinuxDeviceClocks({ "1 1" }, { "boot-a" }).bootCount()), "sig")
        val now = TrustedClock(clocks(Duration.ofDays(1), "1600.0 0", "boot-a"), store(anchor)).now() // 600 s after the anchor
        assertTrue(now.verified)
        assertEquals(server.plusSeconds(600), now.instant)
        assertEquals(loopDoDate(server, ist), loopDoDate(now.instant, ist))
    }

    @Test
    fun `after a reboot the anchor no longer counts, so the tick is unverified`() {
        val anchor = SignedAnchor(TimeAnchor(server, 1_000_000L, LinuxDeviceClocks({ "1 1" }, { "boot-a" }).bootCount()), "sig")
        assertFalse(TrustedClock(clocks(Duration.ZERO, "20.0 0", "boot-b"), store(anchor)).now().verified)
    }
}
