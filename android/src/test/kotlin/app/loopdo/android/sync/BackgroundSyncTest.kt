package app.loopdo.android.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The background sync job keeps the app awake until queued changes reach the server (owner, 2026-11-16). */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSyncTest {
    @Test
    fun waitsUntilTheQueueIsEmpty() = runTest {
        var checks = 0
        assertTrue(awaitUploaded(timeoutMs = 60_000, pollMs = 1_000) { checks++ < 3 }) // 3 checks with changes waiting, then empty
        assertEquals(4, checks)
        assertEquals(3_000, testScheduler.currentTime)
    }

    @Test
    fun givesUpAfterTheTimeoutSoTheJobEnds() = runTest {
        assertFalse(awaitUploaded(timeoutMs = 10_000, pollMs = 1_000) { true }) // offline-ish: the next run tries again
        assertEquals(10_000, testScheduler.currentTime)
    }
}
