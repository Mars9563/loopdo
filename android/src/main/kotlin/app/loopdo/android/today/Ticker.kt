package app.loopdo.android.today

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant

/**
 * Emits "now" immediately, then at every whole minute (for the countdown) and at the
 * exact day end, even when it falls between minutes (DST gaps). [dayEndAt] is read
 * again after every tick, so it moves on to the next day by itself.
 */
fun ticker(now: () -> Instant, dayEndAt: () -> Instant): Flow<Instant> = flow {
    while (true) {
        val t = now()
        emit(t)
        val toMinute = MINUTE_MS - Math.floorMod(t.toEpochMilli(), MINUTE_MS)
        val toEnd = Duration.between(t, dayEndAt()).toMillis()
        delay(if (toEnd in 1 until toMinute) toEnd else toMinute)
    }
}

private const val MINUTE_MS = 60_000L
