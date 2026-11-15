package app.loopdo.shared.streak

import app.loopdo.shared.Limits
import app.loopdo.shared.habit.DayOutcome
import app.loopdo.shared.habit.DayRecord
import java.time.LocalDate

/**
 * A streak as derived from the day history. [frozenDays] are misses that a freeze
 * absorbed (shown as ❄). [daysToNextFreeze] is null when the bank is full.
 */
data class StreakResult(
    val current: Int,
    val best: Int,
    val freezesHeld: Int,
    val frozenDays: Set<LocalDate>,
    val daysToNextFreeze: Int?,
)

/**
 * Walks [records] (ascending dates) and applies the streak + freeze rules.
 * Nothing here is stored: the same history always gives the same answer on every
 * device and on the server, so a streak can't drift or be edited directly.
 */
fun computeStreak(records: List<DayRecord>): StreakResult {
    var current = 0
    var best = 0
    var freezes = 0
    var run = 0 // successes since the last earned freeze or the last miss
    val frozen = mutableSetOf<LocalDate>()

    for ((date, outcome) in records) {
        when (outcome) {
            DayOutcome.SUCCESS -> {
                current++
                run++
                if (run == Limits.FREEZE_EARN_RUN) {
                    if (freezes < Limits.FREEZE_CAP) freezes++
                    run = 0
                }
                best = maxOf(best, current)
            }
            DayOutcome.MISS -> {
                if (freezes > 0) {
                    freezes--
                    frozen += date
                } else {
                    current = 0
                }
                run = 0
            }
            DayOutcome.NEUTRAL, DayOutcome.PENDING -> Unit
        }
    }

    val toNext = if (freezes == Limits.FREEZE_CAP) null else Limits.FREEZE_EARN_RUN - run
    return StreakResult(current, best, freezes, frozen, toNext)
}
