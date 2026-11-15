package app.loopdo.shared.day

import java.time.Instant
import java.time.LocalDate

/**
 * A user's day settings plus an optional change waiting to take effect.
 * Changes never apply mid-day, and the LoopDo date never goes backwards, so a
 * closed day can never reopen.
 *
 * [floorDate]: lowest LoopDo date allowed from now on (left by earlier switches).
 * [pendingFloorDate]: lowest date allowed once the pending change is in force.
 * Floors matter only when new settings lag the old ones by more than a day
 * (e.g. Auckland → Los Angeles plus a later day end); then the current day
 * stretches until the new settings catch up, instead of repeating yesterday.
 */
data class DaySettingsState(
    val current: DaySettings,
    val pending: DaySettings? = null,
    val pendingEffectiveAt: Instant? = null,
    val floorDate: LocalDate? = null,
    val pendingFloorDate: LocalDate? = null,
)

private fun DaySettingsState.pendingDue(t: Instant): Boolean =
    pending != null && pendingEffectiveAt != null && t >= pendingEffectiveAt

/**
 * The settings in force at [now]. A due pending change counts immediately
 * (applied lazily), so correctness never depends on a background job.
 */
fun effectiveSettings(state: DaySettingsState, now: Instant): DaySettings =
    if (state.pendingDue(now)) state.pending!! else state.current

/** The user's LoopDo date at [t]: the date under the settings in force, never below a floor. */
fun currentLoopDoDate(state: DaySettingsState, t: Instant): LocalDate {
    val base = loopDoDate(t, effectiveSettings(state, t))
    val floor = listOfNotNull(state.floorDate, state.pendingFloorDate.takeIf { state.pendingDue(t) }).maxOrNull()
    return if (floor != null && floor > base) floor else base
}

/** Move a pending change that is already in force into [DaySettingsState.current]. */
private fun DaySettingsState.foldDue(now: Instant): DaySettingsState =
    if (!pendingDue(now)) {
        this
    } else {
        DaySettingsState(
            current = pending!!,
            floorDate = listOfNotNull(floorDate, pendingFloorDate).maxOrNull(),
        )
    }

/**
 * Schedule [new] settings. They take effect when today has ended under both the
 * old and the new settings: waiting for only the old day could make it "yesterday"
 * again under the new zone (Cadence migration 004). The date at the moment of the
 * switch becomes the floor for the new settings.
 */
fun scheduleChange(state: DaySettingsState, new: DaySettings, now: Instant): DaySettingsState {
    val base = state.foldDue(now)
    val today = currentLoopDoDate(base, now)
    val effectiveAt = maxOf(
        dayEndInstant(today, base.current),
        dayEndInstant(loopDoDate(now, new), new),
    )
    return base.copy(
        pending = new,
        pendingEffectiveAt = effectiveAt,
        pendingFloorDate = currentLoopDoDate(base, effectiveAt.minusNanos(1)),
    )
}
