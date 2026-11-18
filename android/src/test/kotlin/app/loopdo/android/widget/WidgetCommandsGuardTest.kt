package app.loopdo.android.widget

import app.loopdo.client.notify.TapKind
import app.loopdo.client.notify.WidgetTap

import app.loopdo.client.today.TodayActions
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

private class CountingActions : TodayActions {
    var calls = 0
    override suspend fun tickHabit(habitId: UUID, shownDate: LocalDate?, by: Int): Pair<Int, Int>? { calls++; return 0 to 1 }
    override suspend fun untickHabit(habitId: UUID, shownDate: LocalDate?): Pair<Int, Int>? { calls++; return 1 to 0 }
    override suspend fun setHabitCount(habitId: UUID, count: Int, shownDate: LocalDate?): Pair<Int, Int>? = 0 to count
    override suspend fun completeHabit(habitId: UUID, shownDate: LocalDate?): Pair<Int, Int>? = 0 to 1
    override suspend fun decrementHabit(habitId: UUID, shownDate: LocalDate?): Pair<Int, Int>? { calls++; return null }
    override suspend fun debugForgedTick(habitId: UUID, shownDate: LocalDate?): Pair<Int, Int>? = null
    override suspend fun completeTask(taskId: UUID): Boolean { calls++; return true }
    override suspend fun reopenTask(taskId: UUID): Boolean { calls++; return true }
    override suspend fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: UUID?) = UUID.randomUUID()
    override suspend fun appendJournalLine(text: String) = Unit
}

/** A tap acts only if, at tap time, the app is ready on the day the row showed (final review I5). */
class WidgetCommandsGuardTest {
    private val mon = LocalDate.of(2026, 11, 16)
    private val id = UUID.randomUUID().toString()

    private suspend fun tapWith(readyOn: LocalDate?, kind: TapKind = TapKind.TICK): Pair<Int, Int> {
        val actions = CountingActions()
        var redraws = 0
        WidgetCommands(actions, readyOn = { readyOn }) { redraws++ }.handle(WidgetTap(kind, id, mon, done = false))
        return actions.calls to redraws
    }

    @Test
    fun readyOnTheShownDayActs() = runTest { assertEquals(1 to 1, tapWith(readyOn = mon)) }

    @Test
    fun notReadyWritesNothingButRedraws() = runTest {
        // Signed out, or another account blocked over unsynced changes, before the widget redrew.
        assertEquals(0 to 1, tapWith(readyOn = null))
        assertEquals(0 to 1, tapWith(readyOn = null, kind = TapKind.TASK))
    }

    @Test
    fun aDifferentDayWritesNothingButRedraws() = runTest { assertEquals(0 to 1, tapWith(readyOn = mon.plusDays(1), kind = TapKind.TASK)) }
}
