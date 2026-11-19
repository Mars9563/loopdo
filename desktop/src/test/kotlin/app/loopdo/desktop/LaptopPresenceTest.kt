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

    private class Rig(scope: TestScope, var locked: Boolean = false, var active: Boolean = true, val slow: CompletableDeferred<Unit>? = null, var reaches: Boolean = true) {
        val writes = mutableListOf<Instant>()
        val log = mutableListOf<String>()
        val p = LaptopPresence(
            scope.backgroundScope,
            write = { at -> slow?.await(); if (reaches) { writes += at; log += "write" }; reaches },
            flush = { log += "flush" },
            now = { Instant.parse("2026-11-19T07:00:00Z").plusMillis(scope.currentTime) },
            locked = { locked },
            active = { active },
            tickMs = 30_000,
        )
    }

    @Test
    fun `in use writes two minutes ahead and refreshes every minute`() = runTest {
        val r = Rig(this)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(listOf(t0.plusMillis(30_000).plusSeconds(120)), r.writes, "owner: a closed lid hands over within 2 min")
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, r.writes.size, "90 s of 120 still ahead: nothing to write")
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, r.writes.size, "refreshed once a minute or less is left")
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
        r.p.goingToSleep()
        assertEquals(t0.plusMillis(30_001), r.writes.last())
        r.p.woke(); advanceTimeBy(1); runCurrent()
        val back = r.writes.size
        assertTrue(r.writes.last() > t0.plusMillis(30_001).plusSeconds(100), "awake again: in use")
        r.p.stop()
        assertEquals(back + 1, r.writes.size); assertTrue(r.writes.last() < t0.plusSeconds(60), "quit: now")
    }

    @Test
    fun `nextActiveUntil decides what to write`() {
        assertEquals(t0.plusSeconds(120), nextActiveUntil(inUse = true, now = t0, written = null))
        assertNull(nextActiveUntil(true, t0, t0.plusSeconds(90)), "1.5 min left")
        assertEquals(t0.plusSeconds(120), nextActiveUntil(true, t0, t0.plusSeconds(60)), "1 min left")
        assertEquals(t0, nextActiveUntil(false, t0, t0.plusSeconds(100)), "stopped: now")
        assertNull(nextActiveUntil(false, t0, t0.minusSeconds(10)), "already over")
        assertNull(nextActiveUntil(false, t0, null), "never said anything")
    }
    @Test
    fun `going to sleep writes now and waits for the upload before the laptop may sleep`() = runTest {
        val r = Rig(this)
        advanceTimeBy(30_001); runCurrent()
        r.log.clear()
        r.p.goingToSleep()
        assertEquals(listOf("write", "flush"), r.log, "now, then the upload, before it returns (final review I1)")
    }

    @Test
    fun `quitting writes now and waits for the upload`() = runTest {
        val r = Rig(this)
        advanceTimeBy(30_001); runCurrent()
        r.log.clear()
        r.p.stop()
        assertEquals(listOf("write", "flush"), r.log)
    }

    @Test
    fun `a write that didn't reach the database is tried again`() = runTest {
        val r = Rig(this, reaches = false) // offline or signed out (final review I1, I3)
        advanceTimeBy(30_001); runCurrent()
        assertTrue(r.writes.isEmpty())
        r.reaches = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, r.writes.size, "not counted as written, so the next tick writes")
    }

    @Test
    fun `do not disturb on the laptop hands the pings to the phone`() {
        assertTrue(app.loopdo.desktop.presence.countsAsInUse(pingsOn = true, signedIn = true, blocked = false, doNotDisturb = false))
        kotlin.test.assertFalse(app.loopdo.desktop.presence.countsAsInUse(pingsOn = true, signedIn = true, blocked = false, doNotDisturb = true), "final review I2")
        kotlin.test.assertFalse(app.loopdo.desktop.presence.countsAsInUse(pingsOn = false, signedIn = true, blocked = false, doNotDisturb = false))
        kotlin.test.assertFalse(app.loopdo.desktop.presence.countsAsInUse(pingsOn = true, signedIn = false, blocked = false, doNotDisturb = false))
        kotlin.test.assertFalse(app.loopdo.desktop.presence.countsAsInUse(pingsOn = true, signedIn = true, blocked = true, doNotDisturb = false))
    }

    @Test
    fun `the sleep lock is held while awake and let go once now is out`() {
        val started = mutableListOf<List<String>>()
        var alive = 0
        val inh = app.loopdo.desktop.presence.SleepInhibitor(start = { cmd -> started += cmd; alive++; { alive-- } })
        inh.take()
        assertEquals(listOf("systemd-inhibit", "--what=sleep", "--mode=delay", "--who=loopdo_"), started.single().take(4))
        assertEquals(1, alive)
        inh.take(); assertEquals(1, alive, "one at a time")
        inh.release(); assertEquals(0, alive)
        inh.release(); assertEquals(0, alive)
    }
}
