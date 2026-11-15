package app.loopdo.shared.streak

import app.loopdo.shared.habit.DayOutcome
import app.loopdo.shared.habit.DayRecord
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class StreakTest {
    private val start = LocalDate.of(2026, 11, 1)

    /** "7S M S" = 7 successes, a miss, a success, on consecutive days from Nov 1. */
    private fun run(pattern: String): List<DayRecord> {
        val outcomes = pattern.split(" ").filter { it.isNotBlank() }.flatMap { token ->
            val n = token.dropLast(1).ifEmpty { "1" }.toInt()
            val o = when (token.last()) {
                'S' -> DayOutcome.SUCCESS
                'M' -> DayOutcome.MISS
                'N' -> DayOutcome.NEUTRAL
                'P' -> DayOutcome.PENDING
                else -> error("bad token $token")
            }
            List(n) { o }
        }
        return outcomes.mapIndexed { i, o -> start.plusDays(i.toLong()) to o }
    }

    private fun day(n: Int) = start.plusDays(n - 1L)

    private fun check(pattern: String, current: Int, best: Int, freezes: Int, frozen: Set<Int>, toNext: Int?) =
        assertEquals(
            StreakResult(current, best, freezes, frozen.map(::day).toSet(), toNext),
            computeStreak(run(pattern)),
            pattern,
        )

    @Test fun sevenEarnsOne() = check("7S", 7, 7, 1, emptySet(), 7)

    @Test fun freezeSavesStreak() = check("7S M S", 8, 8, 0, setOf(8), 6)

    @Test fun missWithoutFreezeResets() = check("3S M", 0, 3, 0, emptySet(), 7)

    @Test fun capAtTwo() = check("21S", 21, 21, 2, emptySet(), null)

    @Test fun neutralDoesNotBreak() = check("S N S", 2, 2, 0, emptySet(), 5)

    @Test fun pendingTodayDoesNotBreak() = check("S S P", 2, 2, 0, emptySet(), 5)

    @Test fun secondMissAfterFreezeResets() = check("7S M M", 0, 7, 0, setOf(8), 7)

    @Test fun earnRunRestartsAfterFrozenDay() = check("7S M 6S", 13, 13, 0, setOf(8), 1)

    @Test fun emptyIsZero() = assertEquals(StreakResult(0, 0, 0, emptySet(), 7), computeStreak(emptyList()))
}
