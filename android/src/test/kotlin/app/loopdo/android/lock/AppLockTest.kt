package app.loopdo.android.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Optional app lock (owner, 2026-11-17): off by default; when on, asks after ≥ 1 min in the background. */
class AppLockTest {
    private class Store(override var enabled: Boolean = false) : LockStore
    private var clock = 1_000_000L
    private fun lock(store: Store = Store()) = AppLock(store) { clock }

    @Test
    fun offByDefault() {
        val l = lock()
        assertFalse(l.enabled)
        l.onBackground(); clock += 10 * 60_000; l.onForeground()
        assertFalse(l.locked.value)
    }

    @Test
    fun locksAfterAMinuteAway() {
        val l = lock(Store(enabled = true)).apply { unlocked() }
        l.onBackground(); clock += 60_000; l.onForeground()
        assertTrue(l.locked.value)
    }

    @Test
    fun notBeforeAMinute() {
        val l = lock(Store(enabled = true)).apply { unlocked() }
        l.onBackground(); clock += 59_999; l.onForeground()
        assertFalse(l.locked.value)
    }

    @Test
    fun unlockClears() {
        val l = lock(Store(enabled = true))
        assertTrue("a cold start with the lock on asks first", l.locked.value)
        l.unlocked()
        assertFalse(l.locked.value)
        l.setEnabled(false)
        l.onBackground(); clock += 5 * 60_000; l.onForeground()
        assertFalse(l.locked.value)
    }
}
