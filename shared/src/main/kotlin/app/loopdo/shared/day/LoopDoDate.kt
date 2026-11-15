package app.loopdo.shared.day

import app.loopdo.shared.Limits
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A user's day boundary: their IANA time zone and the wall-clock time their day ends. */
data class DaySettings(val zone: ZoneId, val dayEnd: LocalTime) {
    init {
        require(dayEnd <= LocalTime.of(Limits.DAY_END_MAX_HOUR, 0)) {
            "day end must be between 00:00 and 0${Limits.DAY_END_MAX_HOUR}:00, was $dayEnd"
        }
    }

    companion object {
        fun default(zone: ZoneId) = DaySettings(zone, LocalTime.of(Limits.DEFAULT_DAY_END_HOUR, 0))
    }
}

/**
 * The moment LoopDo date [date] ends: the first instant at which the wall clock
 * on the next calendar day reaches [DaySettings.dayEnd].
 *
 * DST gap (that wall time never happens): the day ends at the transition itself.
 * DST overlap (that wall time happens twice): the first occurrence counts, so a
 * repeated hour can never reopen a day that already ended.
 */
fun dayEndInstant(date: LocalDate, s: DaySettings): Instant {
    val wall = date.plusDays(1).atTime(s.dayEnd)
    val rules = s.zone.rules
    val offsets = rules.getValidOffsets(wall)
    return if (offsets.isEmpty()) {
        rules.getTransition(wall).instant
    } else {
        wall.toInstant(offsets.first())
    }
}

/**
 * The LoopDo date that [instant] belongs to. Because the day end is at most 08:00,
 * the answer is either the local calendar date or the day before it.
 */
fun loopDoDate(instant: Instant, s: DaySettings): LocalDate {
    val local = instant.atZone(s.zone).toLocalDate()
    val yesterday = local.minusDays(1)
    return if (instant >= dayEndInstant(yesterday, s)) local else yesterday
}
