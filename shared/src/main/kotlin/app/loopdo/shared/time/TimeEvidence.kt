package app.loopdo.shared.time

import app.loopdo.shared.Limits
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The last time the phone heard the server's clock, pinned to the phone's
 * since-boot timer (`SystemClock.elapsedRealtime()`), which the user can't change.
 */
data class TimeAnchor(val serverTime: Instant, val elapsedRealtimeMs: Long, val bootCount: Int)

/**
 * Best trustworthy guess of "now" (or of when a tick happened), ignoring the
 * phone's settable wall clock. Network time wins; otherwise anchor + elapsed time,
 * valid only within the same boot. Null when there is no trustworthy source.
 */
fun estimateTime(networkTime: Instant?, anchor: TimeAnchor?, elapsedNowMs: Long, bootCountNow: Int): Instant? {
    if (networkTime != null) return networkTime
    if (anchor == null || anchor.bootCount != bootCountNow) return null
    val delta = elapsedNowMs - anchor.elapsedRealtimeMs
    if (delta < 0) return null
    return anchor.serverTime.plusMillis(delta)
}

/** What the phone sends with every completion. */
data class TickEvidence(
    val claimedDate: LocalDate,
    val wallClock: Instant,
    val networkTime: Instant?,
    val anchor: TimeAnchor?,
    val elapsedAtTickMs: Long,
    val bootCountAtTick: Int,
)

enum class RejectReason { BACKDATED, FUTURE, BEFORE_LAST_ANCHOR }

sealed interface ClaimResult {
    data class Accepted(val verified: Boolean, val estimatedTime: Instant?) : ClaimResult
    data class Rejected(val reason: RejectReason) : ClaimResult
}

/**
 * Server-side check of an (often offline) tick. [dateAt] gives the user's LoopDo
 * date at any instant under the settings in force at that instant (e.g.
 * `{ currentLoopDoDate(state, it) }`), so a time-zone change between the tick and
 * the sync doesn't misjudge an honest tick. Offline ticks are accepted on the day
 * they really happened; a changed phone clock cannot move a tick into a closed day.
 */
fun validateClaim(e: TickEvidence, dateAt: (Instant) -> LocalDate, serverNow: Instant): ClaimResult {
    if (e.claimedDate > dateAt(serverNow)) return ClaimResult.Rejected(RejectReason.FUTURE)

    val est = estimateTime(e.networkTime, e.anchor, e.elapsedAtTickMs, e.bootCountAtTick)
    if (est != null) {
        val slack = Duration.ofSeconds(Limits.TAMPER_TOLERANCE_SECONDS)
        val fits = e.claimedDate == dateAt(est.minus(slack)) || e.claimedDate == dateAt(est.plus(slack))
        return if (fits) ClaimResult.Accepted(true, est) else ClaimResult.Rejected(RejectReason.BACKDATED)
    }

    // No trustworthy clock (rebooted while offline): owner chose "accept, mark unverified",
    // but never earlier than the last day the phone saw from the server.
    if (dateAt(e.wallClock) != e.claimedDate) return ClaimResult.Rejected(RejectReason.BACKDATED)
    if (e.anchor != null && e.claimedDate < dateAt(e.anchor.serverTime)) {
        return ClaimResult.Rejected(RejectReason.BEFORE_LAST_ANCHOR)
    }
    return ClaimResult.Accepted(false, null)
}
