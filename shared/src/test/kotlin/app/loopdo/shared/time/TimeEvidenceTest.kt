package app.loopdo.shared.time

import app.loopdo.shared.day.DaySettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeEvidenceTest {
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    private fun at(s: String) = Instant.parse(s)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    private val anchorSat = TimeAnchor(at("2026-11-14T12:30:00Z"), 1_000_000, 5) // Sat 18:00 IST
    private val sunMorning = at("2026-11-15T03:30:00Z") // Sun 09:00 IST

    @Test
    fun offlineEveningTickSyncedNextMorningIsVerified() { // 23:00 IST Sat, 5 h after the anchor
        val e = TickEvidence(d(14), at("2026-11-14T17:30:00Z"), null, anchorSat, 1_000_000 + 18_000_000, 5)
        assertEquals(ClaimResult.Accepted(true, at("2026-11-14T17:30:00Z")), validateClaim(e, ist, sunMorning))
    }

    @Test
    fun clockSetBackIsRejected() { // anchor Sun 10:00 IST; phone clock claims Fri 22:00 IST
        val a = TimeAnchor(at("2026-11-15T04:30:00Z"), 1_000_000, 5)
        val e = TickEvidence(d(13), at("2026-11-13T16:30:00Z"), null, a, 1_060_000, 5)
        assertEquals(ClaimResult.Rejected(RejectReason.BACKDATED), validateClaim(e, ist, at("2026-11-15T05:00:00Z")))
    }

    @Test
    fun networkTimeBeatsWallClock() {
        val net = at("2026-11-15T04:31:00Z") // Sun 10:01 IST
        val serverNow = at("2026-11-15T05:00:00Z")
        val fri = TickEvidence(d(13), at("2026-11-13T16:30:00Z"), net, null, 0, 1)
        assertEquals(ClaimResult.Rejected(RejectReason.BACKDATED), validateClaim(fri, ist, serverNow))
        val sun = fri.copy(claimedDate = d(15))
        assertEquals(ClaimResult.Accepted(true, net), validateClaim(sun, ist, serverNow))
    }

    @Test
    fun rebootedOfflineIsUnverified() { // boot 6 != anchor boot 5, no network time; 21:00 IST Sat
        val e = TickEvidence(d(14), at("2026-11-14T15:30:00Z"), null, anchorSat, 50_000, 6)
        assertEquals(ClaimResult.Accepted(false, null), validateClaim(e, ist, sunMorning))
    }

    @Test
    fun unverifiedCannotPredateLastAnchor() { // claims Thu 12, but the phone already saw Sat 14 from the server
        val e = TickEvidence(d(12), at("2026-11-12T16:30:00Z"), null, anchorSat, 50_000, 6)
        assertEquals(ClaimResult.Rejected(RejectReason.BEFORE_LAST_ANCHOR), validateClaim(e, ist, sunMorning))
    }

    @Test
    fun unverifiedWallClockMustMatchClaim() {
        val e = TickEvidence(d(14), at("2026-11-15T03:00:00Z"), null, anchorSat, 50_000, 6) // wall = Sun
        assertEquals(ClaimResult.Rejected(RejectReason.BACKDATED), validateClaim(e, ist, sunMorning))
    }

    @Test
    fun futureClaimRejected() {
        val e = TickEvidence(d(15), at("2026-11-15T03:00:00Z"), null, null, 0, 1)
        assertEquals(ClaimResult.Rejected(RejectReason.FUTURE), validateClaim(e, ist, at("2026-11-14T12:30:00Z")))
    }

    @Test
    fun toleranceAcrossDayEnd() { // Sat ends 2026-11-14T22:30Z
        val inside = TickEvidence(d(14), at("2026-11-14T22:31:00Z"), at("2026-11-14T22:31:00Z"), null, 0, 1)
        assertEquals(ClaimResult.Accepted(true, at("2026-11-14T22:31:00Z")), validateClaim(inside, ist, sunMorning))
        val outside = inside.copy(networkTime = at("2026-11-14T22:33:00Z"))
        assertEquals(ClaimResult.Rejected(RejectReason.BACKDATED), validateClaim(outside, ist, sunMorning))
    }

    @Test
    fun staleAnchorSameBootStillVerified() { // offline 3 weeks, never rebooted
        val a = TimeAnchor(at("2026-10-24T12:30:00Z"), 1_000, 5)
        val e = TickEvidence(d(14), at("2026-11-14T17:30:00Z"), null, a, 1_000 + 21L * 86_400_000 + 18_000_000, 5)
        assertEquals(ClaimResult.Accepted(true, at("2026-11-14T17:30:00Z")), validateClaim(e, ist, sunMorning))
    }

    @Test
    fun estimateIgnoresAnchorFromAnotherBoot() =
        assertEquals(null, estimateTime(null, anchorSat, 2_000_000, 6))

    @Test
    fun estimateIgnoresElapsedGoingBackwards() =
        assertEquals(null, estimateTime(null, anchorSat, 999_999, 5))
}
