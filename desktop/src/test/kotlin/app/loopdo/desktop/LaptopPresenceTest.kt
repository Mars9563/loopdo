package app.loopdo.desktop

import app.loopdo.desktop.presence.LaptopPresence
import app.loopdo.desktop.presence.nextActiveUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D7: the laptop's "in use" signal (unlocked, awake, pings on, signed in). */
@OptIn(ExperimentalCoroutinesApi::class)
class LaptopPresenceTest {
    private val t0 = Instant.parse("2026-11-19T07:00:00Z")
    private val min = 60_000L

    private class Rig(scope: TestScope, var locked: Boolean = false, var active: Boolean = true, val slow: CompletableDeferred<Unit>? = null) {
        val writes = mutableListOf<Instant>()
        val p = LaptopPresence(
            scope.backgroundScope,
            write = { at -> slow?.await(); writes += at },
            now = { Instant.parse("2026-11-19T07:00:00Z").plusMillis(scope.currentTime) },
            locked = { locked },
            active = { active },
            tickMs = 30_000,
        )
    }

    @Test
    fun `in use writes five minutes ahead and refreshes every three`() = runTest {
        val r = Rig(this)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(listOf(t0.plusMillis(30_000).plusSeconds(300)), r.writes)
        advanceTimeBy(2 * min); runCurrent()
        assertEquals(1, r.writes.size, "4 of 5 minutes still ahead: nothing to write")
        advanceTimeBy(min); runCurrent()
        assertEquals(2, r.writes.size, "refreshed once under 2 minutes are left")
    }

    @Test
    fun `locking writes now at once`() = runTest {
        val r = Rig(this)
        advanceTimeBy(30_001); runCurrent()
        advanceTimeBy(5_000)
        r.locked = true; r.p.lockChanged(true); runCurrent()
        assertEquals(t0.plusMillis(35_001), r.writes.last(), "no wait for the next tick")
    }

    @Test
    fun `a lock during a refresh still ends with now`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, slow = gate)
        advanceTimeBy(30_001); runCurrent() // the in-use write is stuck in flight
        r.locked = true; r.p.lockChanged(true); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(2, r.writes.size)
        assertTrue(r.writes[0] > r.writes[1], "the +5 min went first, now last: ${r.writes}")
    }

    @Test
    fun `pings off or signed out is not in use`() = runTest {
        val r = Rig(this)
        advanceTimeBy(30_001); runCurrent()
        r.active = false; advanceTimeBy(30_000); runCurrent()
        assertEquals(t0.plusMillis(60_000), r.writes.last(), "at the next tick")
        val n = r.writes.size
        advanceTimeBy(10 * min); runCurrent()
        assertEquals(n, r.writes.size, "nothing more while not in use")
    }

    @Test
    fun `sleep and quit write now`() = runTest {
        val r = Rig(this)
        advanceTimeBy(30_001); runCurrent()
        r.p.sleeping(); runCurrent()
        assertEquals(t0.plusMillis(30_001), r.writes.last())
        r.p.woke(); advanceTimeBy(1); runCurrent()
        val back = r.writes.size
        assertTrue(r.writes.last() > t0.plusMillis(30_001).plusSeconds(200), "awake again: in use")
        r.p.stop()
        assertEquals(back + 1, r.writes.size); assertTrue(r.writes.last() < t0.plusSeconds(60), "quit: now")
    }

    @Test
    fun `nextActiveUntil decides what to write`() {
        assertEquals(t0.plusSeconds(300), nextActiveUntil(inUse = true, now = t0, written = null))
        assertNull(nextActiveUntil(true, t0, t0.plusSeconds(240)), "4 min left")
        assertEquals(t0.plusSeconds(300), nextActiveUntil(true, t0, t0.plusSeconds(60)), "1 min left")
        assertEquals(t0, nextActiveUntil(false, t0, t0.plusSeconds(200)), "stopped: now")
        assertNull(nextActiveUntil(false, t0, t0.minusSeconds(10)), "already over")
        assertNull(nextActiveUntil(false, t0, null), "never said anything")
    }
}
