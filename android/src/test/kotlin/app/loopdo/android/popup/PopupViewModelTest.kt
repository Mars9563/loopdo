package app.loopdo.android.popup

import app.loopdo.android.today.TodayActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class MemoryDrafts : PopupDrafts {
    val map = mutableMapOf<String, String>()
    override fun load(key: String) = map[key].orEmpty()
    override fun save(key: String, text: String) { if (text.isEmpty()) map.remove(key) else map[key] = text }
}

private class FakeActions : TodayActions {
    var fail: String? = null
    val journalSaves = mutableListOf<Triple<LocalDate, String, Int?>>()
    private fun maybeFail() { fail?.let { throw IllegalArgumentException(it) } }
    override suspend fun tickHabit(habitId: UUID, shownDate: LocalDate?) = null
    override suspend fun untickHabit(habitId: UUID, shownDate: LocalDate?) = null
    override suspend fun decrementHabit(habitId: UUID, shownDate: LocalDate?) = null
    override suspend fun debugForgedTick(habitId: UUID, shownDate: LocalDate?) = null
    override suspend fun completeTask(taskId: UUID) = false
    override suspend fun reopenTask(taskId: UUID) = false
    override suspend fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: UUID?): UUID { maybeFail(); return UUID.randomUUID() }
    override suspend fun appendJournalLine(text: String) = maybeFail()
    override suspend fun saveJournal(date: LocalDate, text: String, mood: Int?) { maybeFail(); journalSaves += Triple(date, text, mood) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PopupViewModelTest {
    private val drafts = MemoryDrafts()
    private val actions = FakeActions()
    private var redraws = 0
    private fun vm() = PopupViewModel(actions, drafts) { redraws++ }

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun reset() = Dispatchers.resetMain()

    @Test
    fun dismissedPopupKeepsItsDraft() {
        vm().keepDraft("add", "buy printer ink")
        assertEquals("buy printer ink", vm().draft("add")) // the next time the pop-up opens
    }

    @Test
    fun failedAddKeepsDraftAndShowsError() = runTest {
        val vm = vm()
        vm.keepDraft("add", "x".repeat(301))
        actions.fail = "that's too long. keep it under 300 characters."
        var saved = 0
        vm.addTask("x".repeat(301), null, null, null) { saved++ }
        assertEquals("that's too long. keep it under 300 characters.", vm.error.value)
        assertEquals(0, saved)
        assertEquals("x".repeat(301), drafts.load("add"))
        assertEquals(0, redraws)
    }

    @Test
    fun successClearsDraftAndRedraws() = runTest {
        val vm = vm()
        vm.keepDraft("add", "buy printer ink")
        var saved = 0
        vm.addTask("buy printer ink", LocalDate.of(2026, 11, 15), null, null) { saved++ }
        assertEquals("", drafts.load("add"))
        assertEquals(1, redraws)
        assertEquals(1, saved)
        assertEquals(null, vm.error.value)
    }

    @Test
    fun failedJournalSaveKeepsText() = runTest {
        val vm = vm()
        val sat = LocalDate.of(2026, 11, 14)
        vm.keepDraft(PopupViewModel.journalKey(sat), "a long day")
        actions.fail = "today's entry is full (20000 characters)."
        var saved = 0
        vm.saveJournal(sat, "a long day", 4) { saved++ }
        assertEquals("today's entry is full (20000 characters).", vm.error.value)
        assertEquals("a long day", drafts.load("journal:2026-11-14"))
        assertEquals(0, saved)
    }

    @Test
    fun journalSavesToTheDateItOpenedOn() = runTest {
        // Opened on sat 14, saved after midnight: the activity passes the date it captured at open.
        val opened = LocalDate.of(2026, 11, 14)
        val vm = vm()
        vm.keepDraft(PopupViewModel.journalKey(opened), "late night")
        vm.saveJournal(opened, "late night", 4) {}
        assertEquals(listOf(Triple<LocalDate, String, Int?>(opened, "late night", 4)), actions.journalSaves)
        assertEquals("", drafts.load("journal:2026-11-14"))
        assertEquals(1, redraws)
    }
}
