package app.loopdo.android.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetStatesTest {
    /** A live widget session must re-read the data on every change; one read at session start goes stale (M4 Task 4 bug). */
    @Test
    fun everyChangeRebuildsTheState() = runTest(UnconfinedTestDispatcher()) {
        var reads = 0
        val changes = MutableSharedFlow<Unit>()
        val seen = mutableListOf<WidgetState>()
        val job = launch {
            widgetStates(changes) { reads++; if (reads == 1) WidgetState.Restoring else WidgetState.SignedOut }.toList(seen)
        }
        changes.emit(Unit) // e.g. a tick wrote to the database
        assertEquals(listOf(WidgetState.Restoring, WidgetState.SignedOut), seen)
        job.cancel()
    }
}
