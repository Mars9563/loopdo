package app.loopdo.android.today

import app.loopdo.android.sync.SetAside
import app.loopdo.android.sync.SetAsideNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeActions : TodayActions {
    var fail: String? = null
    var staleTicks = false
    private fun maybeFail() { fail?.let { throw IllegalArgumentException(it) } }
    override suspend fun tickHabit(habitId: UUID, shownDate: LocalDate?): Pair<Int, Int>? {
        if (staleTicks) throw StaleDayException(LocalDate.of(2026, 11, 15))
        return 0 to 1
    }
    override suspend fun untickHabit(habitId: UUID, shownDate: LocalDate?) = 1 to 0
    override suspend fun decrementHabit(habitId: UUID, shownDate: LocalDate?) = 1 to 0
    override suspend fun debugForgedTick(habitId: UUID, shownDate: LocalDate?) = 0 to 1
    override suspend fun saveJournal(date: LocalDate, text: String, mood: Int?) = maybeFail()
    override suspend fun completeTask(taskId: UUID) = true
    override suspend fun reopenTask(taskId: UUID) = true
    override suspend fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: UUID?): UUID { maybeFail(); return UUID.randomUUID() }
    override suspend fun appendJournalLine(text: String) = maybeFail()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    private val today = LocalDate.of(2026, 11, 15)

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun reset() = Dispatchers.resetMain()

    @Test
    fun theSheetClosesOnlyWhenTheTextIsSaved() = runTest {
        val actions = FakeActions()
        val vm = TodayViewModel(emptyFlow(), actions, SetAsideNotices()) { Instant.EPOCH }
        var saved = 0
        actions.fail = "today's entry is full (20000 characters)."
        vm.appendJournal("one more line") { saved++ }
        vm.addTask("x".repeat(301), null, null, null, null, today) { saved++ }
        assertEquals(0, saved) // the add sheet stays open with the words in it
        assertEquals("today's entry is full (20000 characters).", vm.snack.value?.text)
        actions.fail = null
        vm.appendJournal("one more line") { saved++ }
        assertEquals(1, saved)
    }

    @Test
    fun setAsideNoticeIsShownAndConsumed() = runTest {
        val notices = SetAsideNotices()
        notices.add(SetAside("INVALID_EVIDENCE", keptDraft = false)) // happened before the screen opened
        val vm = TodayViewModel(emptyFlow(), FakeActions(), notices) { Instant.EPOCH }
        assertEquals("the server couldn't confirm when that tick happened, so it was undone.", vm.snack.value?.text)
        assertEquals(emptyList(), notices.pending.value)
    }

    @Test
    fun staleTapShowsTheNewDay() = runTest {
        val actions = FakeActions().apply { staleTicks = true }
        val vm = TodayViewModel(emptyFlow(), actions, SetAsideNotices()) { Instant.EPOCH }
        vm.tick(HabitItem(UUID.randomUUID().toString(), "gym", app.loopdo.shared.habit.HabitKind.YES_NO, 0, 1, 0, null))
        assertEquals("the day just ended, so that wasn't ticked. this is the new day.", vm.snack.value?.text)
    }
}
