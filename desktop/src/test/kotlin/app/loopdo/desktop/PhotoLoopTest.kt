package app.loopdo.desktop

import app.loopdo.desktop.journal.PhotoLoop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoLoopTest {
    @Test
    fun `a kick uploads at once and pending photos are retried until they're gone`() = runTest {
        var runs = 0
        var pending = 2L
        val loop = PhotoLoop(backgroundScope, run = { runs++; if (runs >= 3) pending = 0; 0 }, pending = { pending }, every = 300_000, firstRetry = 30_000)
        loop.kick(); runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(2, runs, "retried after 30 s")
        advanceTimeBy(60_001); runCurrent()
        assertEquals(3, runs, "then after 60 s")
        advanceTimeBy(200_000); runCurrent()
        assertEquals(3, runs, "nothing pending: waits for the 5-minute round")
        advanceTimeBy(100_000); runCurrent()
        assertEquals(4, runs, "the 5-minute round")
    }
}
