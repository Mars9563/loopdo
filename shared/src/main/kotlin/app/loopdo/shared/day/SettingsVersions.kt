package app.loopdo.shared.day

import java.time.Instant
import java.time.LocalDate

/**
 * One row of a user's day-settings history, as the server stores it. Versions
 * never change once in force, so any past instant can be judged with the settings
 * that were in force then (an offline tick synced after a time-zone change).
 * The first version has [effectiveAt] = [Instant.EPOCH].
 */
data class DaySettingsVersion(
    val effectiveAt: Instant,
    val settings: DaySettings,
    /** Lowest LoopDo date while this version is in force (see [DaySettingsState.floorDate]). */
    val floorDate: LocalDate?,
)

private fun List<DaySettingsVersion>.inForceAt(t: Instant): DaySettingsVersion {
    require(isNotEmpty()) { "a user always has at least one day-settings version" }
    val sorted = sortedBy { it.effectiveAt }
    return sorted.lastOrNull { it.effectiveAt <= t } ?: sorted.first()
}

/** "What was the user's LoopDo date at instant t?", answered from the history. */
fun dateResolver(versions: List<DaySettingsVersion>): (Instant) -> LocalDate {
    val snapshot = versions.toList()
    return { t ->
        val v = snapshot.inForceAt(t)
        val date = loopDoDate(t, v.settings)
        val floor = v.floorDate
        if (floor != null && floor > date) floor else date
    }
}

/**
 * The version to store when the user asks for [new] settings at [now]. Any version
 * not yet in force is replaced (the caller deletes rows with effectiveAt > now),
 * matching [scheduleChange].
 */
fun nextVersion(versions: List<DaySettingsVersion>, new: DaySettings, now: Instant): DaySettingsVersion {
    val current = versions.inForceAt(now)
    val state = DaySettingsState(current = current.settings, floorDate = current.floorDate)
    val scheduled = scheduleChange(state, new, now)
    return DaySettingsVersion(scheduled.pendingEffectiveAt!!, new, scheduled.pendingFloorDate)
}

/** When the LoopDo day that [t] belongs to ends, under the settings in force at [t]. */
fun dayEndAt(versions: List<DaySettingsVersion>, t: Instant): Instant =
    dayEndInstant(dateResolver(versions)(t), versions.inForceAt(t).settings)
