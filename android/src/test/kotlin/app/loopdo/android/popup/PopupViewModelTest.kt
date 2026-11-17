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
    override fun keys(): Set<String> = map.keys
    override fun save(key: String, text: String) { if (text.isEmpty()) map.remove(key) else map[key] = text }
}

private class FakeActions : TodayActions {
    var fail: String? = null
    val journalSaves = mutableListOf<Triple<LocalDate, String, Int?>>()
    private fun maybeFail() { fail?.let { throw IllegalArgumentException(it) } }
    override suspend fun tickHabit(habitId: UUID, shownDate: LocalDate?, by: Int) = null
    override suspend fun untickHabit(habitId: UUID, shownDate: LocalDate?) = null
    override suspend fun setHabitCount(habitId: UUID, count: Int, shownDate: LocalDate?): Pair<Int, Int>? = 0 to count
    override suspend fun completeHabit(habitId: UUID, shownDate: LocalDate?): Pair<Int, Int>? = 0 to 1
    override suspend fun decrementHabit(habitId: UUID, shownDate: LocalDate?) = null
    override suspend fun debugForgedTick(habitId: UUID, shownDate: LocalDate?) = null
    override suspend fun completeTask(taskId: UUID) = false
    override suspend fun reopenTask(taskId: UUID) = false
    override suspend fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: UUID?): UUID { maybeFail(); return UUID.randomUUID() }
    override suspend fun appendJournalLine(text: String) = maybeFail()
    override suspend fun saveJournal(date: LocalDate, text: String, mood: Int?, base: String?) { maybeFail(); journalSaves += Triple(date, text, mood) }
    val lineSaves = mutableListOf<Triple<LocalDate, String, java.time.Instant?>>()
    override suspend fun addJournalLines(date: LocalDate, text: String, at: java.time.Instant?) { maybeFail(); lineSaves += Triple(date, text, at) }
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

    // M6: the pop-up writes one new line; its draft is that line plus when it was last edited.
    @Test
    fun lineDraftKeepsItsLastEditTime() {
        val day = LocalDate.of(2026, 11, 14)
        val at = java.time.Instant.parse("2026-11-14T17:00:00Z")
        vm().keepLineDraft(day, "half a thought", at)
        assertEquals("half a thought" to at, vm().lineDraft(day))
        assertEquals(setOf(day), vm().journalDraftDates())
    }

    @Test
    fun earlierDayLineSavesWithItsDraftTime() = runTest {
        val friday = LocalDate.of(2026, 11, 13)
        val at = java.time.Instant.parse("2026-11-13T17:00:00Z")
        val vm = vm()
        vm.keepLineDraft(friday, "draft from last night", at)
        vm.saveLine(friday, "draft from last night", earlierDay = true) {}
        assertEquals(listOf(Triple<LocalDate, String, java.time.Instant?>(friday, "draft from last night", at)), actions.lineSaves)
        assertEquals("" to null, vm.lineDraft(friday))
        assertEquals(emptySet<LocalDate>(), vm.journalDraftDates())
        // Today's line is stamped when it is saved (no draft time passed on).
        vm.saveLine(friday.plusDays(1), "now", earlierDay = false) {}
        assertEquals(null, actions.lineSaves.last().third)
    }

    @Test
    fun oldWholeEntryDraftBecomesOnlyTheNewText() {
        // A draft from before M6 held the whole entry (with its base): only what was typed after the base is kept.
        val day = LocalDate.of(2026, 11, 14)
        vm().keepJournalDraft(day, "old entry\nnew words", "old entry")
        assertEquals("new words", vm().lineDraft(day).first)
    }

    // Review of M6, critical 1: an earlier day's draft edited after its day, or from before M6, still saves.
    @Test
    fun editedOrUntimedEarlierDraftStillSaves() = runTest {
        val friday = LocalDate.of(2026, 11, 13)
        val fridayNight = java.time.Instant.parse("2026-11-13T18:20:00Z")
        val vm = vm()
        vm.keepLineDraft(friday, "long day", fridayNight)
        vm.keepLineDraft(friday, "long day.", java.time.Instant.parse("2026-11-14T04:00:00Z"), earlierDay = true)
        vm.saveLine(friday, "long day.", earlierDay = true) {}
        assertEquals(fridayNight, actions.lineSaves.last().third) // still its own day's time
        vm.keepJournalDraft(friday, "old entry\nfrom before the stamps", "old entry") // no time known
        vm.saveLine(friday, "from before the stamps", earlierDay = true) {}
        assertEquals(java.time.Instant.EPOCH, actions.lineSaves.last().third) // "unknown": stamped at the day's end
    }
}
