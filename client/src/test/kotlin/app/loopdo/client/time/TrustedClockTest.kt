package app.loopdo.client.time


import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.loopDoDate
import app.loopdo.shared.time.ClaimResult
import app.loopdo.shared.time.TickEvidence
import app.loopdo.shared.time.TimeAnchor
import app.loopdo.shared.time.validateClaim
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeClocks(
    var wall: Instant,
    var elapsed: Long,
    var boot: Int,
    var network: Instant? = null,
) : DeviceClocks {
    override fun wallClock() = wall
    override fun elapsedRealtimeMs() = elapsed
    override fun bootCount() = boot
    override fun networkTime() = network
}

private class MemoryAnchors(var anchor: SignedAnchor? = null) : AnchorStore {
    override fun current() = anchor
    override fun save(a: SignedAnchor) { anchor = a }
}

class TrustedClockTest {
    private val serverTime = Instant.parse("2026-11-15T12:30:00Z") // Sun 18:00 IST
    private val signed = SignedAnchor(TimeAnchor(serverTime, 1_000, 5), "sig-from-server")
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))

    @Test
    fun nowFollowsAnchorNotWallClock() {
        // The user set the phone clock back a day; the same-boot anchor still gives the truth.
        val clocks = FakeClocks(wall = serverTime.minusSeconds(86_400), elapsed = 61_000, boot = 5)
        assertEquals(TrustedNow(serverTime.plusSeconds(60), verified = true), TrustedClock(clocks, MemoryAnchors(signed)).now())
    }

    @Test
    fun networkTimePreferred() {
        val net = Instant.parse("2026-11-15T13:00:00Z")
        val clocks = FakeClocks(wall = Instant.EPOCH, elapsed = 61_000, boot = 5, network = net)
        assertEquals(TrustedNow(net, verified = true), TrustedClock(clocks, MemoryAnchors(signed)).now())
    }

    @Test
    fun noTrustedSourceFallsBackToWallClockUnverified() {
        val wall = Instant.parse("2026-11-15T14:00:00Z")
        val clocks = FakeClocks(wall = wall, elapsed = 500, boot = 6) // rebooted, offline
        assertEquals(TrustedNow(wall, verified = false), TrustedClock(clocks, MemoryAnchors(signed)).now())
    }

    @Test
    fun evidenceAfterOfflineRebootHasNoAnchorProof() {
        val clocks = FakeClocks(wall = Instant.parse("2026-11-15T15:30:00Z"), elapsed = 50_000, boot = 6)
        val e = EvidenceFactory(clocks, MemoryAnchors(signed)).forTick(LocalDate.of(2026, 11, 15))
        // The anchor is still sent (it bounds how far back an unverified tick may claim), but from boot 5.
        assertEquals(5, e.getValue("anchor").jsonObject.getValue("bootCount").jsonPrimitive.int)
        assertEquals(6, e.getValue("bootCountAtTick").jsonPrimitive.int)
        assertNull(e["networkTime"])
        assertEquals(setOf("claimedDate", "wallClock", "anchor", "elapsedAtTickMs", "bootCountAtTick"), e.keys)
    }

    @Test
    fun evidenceMatchesServerValidator() {
        // Honest offline tick at 23:00 IST Sun, 5 h after the anchor, same boot.
        val clocks = FakeClocks(wall = Instant.parse("2026-11-15T17:30:00Z"), elapsed = 1_000 + 18_000_000, boot = 5)
        val e = EvidenceFactory(clocks, MemoryAnchors(signed)).forTick(LocalDate.of(2026, 11, 15))
        val a = e.getValue("anchor").jsonObject
        val decoded = TickEvidence(
            LocalDate.parse(e.getValue("claimedDate").jsonPrimitive.content),
            Instant.parse(e.getValue("wallClock").jsonPrimitive.content),
            null,
            TimeAnchor(Instant.parse(a.getValue("serverTime").jsonPrimitive.content), a.getValue("elapsedRealtimeMs").jsonPrimitive.long, a.getValue("bootCount").jsonPrimitive.int),
            e.getValue("elapsedAtTickMs").jsonPrimitive.long,
            e.getValue("bootCountAtTick").jsonPrimitive.int,
        )
        val result = validateClaim(decoded, { loopDoDate(it, ist) }, Instant.parse("2026-11-16T03:00:00Z"))
        assertEquals(ClaimResult.Accepted(true, Instant.parse("2026-11-15T17:30:00Z")), result)
        assertEquals("sig-from-server", a.getValue("sig").jsonPrimitive.content)
    }

    @Test
    fun refresherReadsClocksWhenTheResponseArrives() = runTest {
        val clocks = FakeClocks(wall = Instant.EPOCH, elapsed = 1_000, boot = 5)
        val store = MemoryAnchors()
        val api = TimeApi { clocks.elapsed = 1_750; serverTime to "sig" } // the call takes 750 ms
        AnchorRefresher(api, store, clocks).refresh()
        assertEquals(SignedAnchor(TimeAnchor(serverTime, 1_750, 5), "sig"), store.anchor)
    }
}
