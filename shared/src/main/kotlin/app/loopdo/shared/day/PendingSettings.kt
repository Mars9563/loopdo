package app.loopdo.shared.day

import java.time.Instant

/**
 * A user's day settings plus an optional change waiting to take effect.
 * Changes never apply mid-day, so a closed LoopDo day can never reopen.
 */
data class DaySettingsState(
    val current: DaySettings,
    val pending: DaySettings? = null,
    val pendingEffectiveAt: Instant? = null,
)

/**
 * The settings in force at [now]. A due pending change counts immediately
 * (applied lazily), so correctness never depends on a background job.
 */
fun effectiveSettings(state: DaySettingsState, now: Instant): DaySettings {
    val pending = state.pending ?: return state.current
    val at = state.pendingEffectiveAt ?: return state.current
    return if (now >= at) pending else state.current
}

/**
 * Schedule [new] settings. They take effect when today has ended under BOTH the
 * old and the new settings: waiting for only the old day could make it "yesterday"
 * again under the new zone (Cadence migration 004).
 */
fun scheduleChange(state: DaySettingsState, new: DaySettings, now: Instant): DaySettingsState {
    val cur = effectiveSettings(state, now)
    val endUnderCurrent = dayEndInstant(loopDoDate(now, cur), cur)
    val endUnderNew = dayEndInstant(loopDoDate(now, new), new)
    return DaySettingsState(
        current = cur,
        pending = new,
        pendingEffectiveAt = maxOf(endUnderCurrent, endUnderNew),
    )
}
