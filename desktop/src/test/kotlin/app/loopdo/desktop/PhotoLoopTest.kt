package app.loopdo.desktop

import app.loopdo.desktop.journal.PhotoLoop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoLoopTest {
    @Test
    fun `while photos keep failing, retries double up to the cap and stay there`() = runTest {
        val runs = mutableListOf<Long>()
        PhotoLoop(backgroundScope, run = { runs += currentTime; 0 }, pending = { 1L }, every = 300_000, firstRetry = 30_000)
        advanceTimeBy(300_000 + 30_000 + 60_000 + 120_000 + 240_000 + 300_000 + 300_000 + 1)
        val gaps = runs.zipWithNext { a, b -> b - a }
        assertEquals(listOf(30_000L, 60_000, 120_000, 240_000, 300_000, 300_000), gaps)
    }
}
