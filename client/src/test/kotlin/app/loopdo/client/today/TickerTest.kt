package app.loopdo.client.today


import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TickerTest {
    private val start = Instant.parse("2026-11-14T22:28:20Z") // 03:58:20 IST, day ends 04:00

    @Test
    fun emitsEveryMinuteAndExactlyAtDayEnd() = runTest {
        val dayEnd = Instant.parse("2026-11-14T22:30:00Z")
        val now = { start.plusMillis(testScheduler.currentTime) }
        val ticks = ticker(now) { dayEnd }.take(4).toList()
        assertEquals(
            listOf("2026-11-14T22:28:20Z", "2026-11-14T22:29:00Z", "2026-11-14T22:30:00Z", "2026-11-14T22:31:00Z").map(Instant::parse),
            ticks,
        )
    }

    @Test
    fun dayEndBetweenMinutesIsNotMissed() = runTest {
        // A DST gap can end a day off the minute grid; the ticker must still wake for it.
        val dayEnd = Instant.parse("2026-11-14T22:29:30.500Z")
        val now = { start.plusMillis(testScheduler.currentTime) }
        val ticks = ticker(now) { dayEnd }.take(4).toList()
        assertEquals(Instant.parse("2026-11-14T22:29:30.500Z"), ticks[2])
        assertEquals(Instant.parse("2026-11-14T22:30:00Z"), ticks[3])
    }
}
