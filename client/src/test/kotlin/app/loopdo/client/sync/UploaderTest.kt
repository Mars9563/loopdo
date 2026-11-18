package app.loopdo.client.sync



import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeTx(override val id: Int, override val ops: List<PendingOp>) : PendingTransaction {
    var completed = false
    override suspend fun complete() { completed = true }
}

private class FakeApi(var reply: () -> HttpResult) : UploadApi {
    val bodies = mutableListOf<kotlinx.serialization.json.JsonObject>()
    override suspend fun upload(body: kotlinx.serialization.json.JsonObject): HttpResult { bodies += body; return reply() }
}

private class MemoryDrafts : DraftStore {
    val saved = mutableListOf<Draft>()
    override suspend fun save(kind: String, payload: String, reason: String) { saved += Draft(kind, payload, reason) }
}

private class MemoryState : UploadState {
    val first = mutableMapOf<Int, Instant>()
    override suspend fun firstFailure(txId: Int) = first[txId]
    override suspend fun markFailure(txId: Int, at: Instant) { first.putIfAbsent(txId, at) }
    override suspend fun clear(txId: Int) { first.remove(txId) }
}

class UploaderTest {
    private var now = Instant.parse("2026-11-15T10:00:00Z")
    private val journalOp = PendingOp("PUT", "journal_entries", "j-1", mapOf("loopdo_date" to "2026-11-15", "text" to "pizza was good"))
    private val habitOp = PendingOp("PUT", "habits", "h-1", mapOf("title" to "gym", "kind" to "yes_no", "target" to 1L))

    private fun uploader(api: UploadApi, drafts: DraftStore = MemoryDrafts(), state: UploadState = MemoryState()) =
        Uploader(api, drafts, state) { now }

    @Test
    fun oneTransactionPerUpload() = runTest {
        val api = FakeApi { HttpResult(200, """{"applied":2}""") }
        val tx = FakeTx(1, listOf(habitOp, journalOp))
        uploader(api).upload(tx)
        assertEquals(1, api.bodies.size)
        assertEquals(2, (api.bodies.single()["ops"] as kotlinx.serialization.json.JsonArray).size)
        assertTrue(tx.completed)
    }

    @Test
    fun rejectedTransactionTextBecomesDraft() = runTest {
        val drafts = MemoryDrafts()
        val tx = FakeTx(2, listOf(journalOp))
        uploader(FakeApi { HttpResult(422, """{"code":"BAD_OP","opIndex":0}""") }, drafts).upload(tx)
        assertTrue(tx.completed) // discarded from the queue...
        assertTrue(Draft("journal_entries.text", "pizza was good", "BAD_OP") in drafts.saved) // ...but the words are kept
    }

    @Test
    fun rejectionWithoutTypedTextKeepsOnlyTheTransaction() = runTest {
        val drafts = MemoryDrafts()
        val tick = PendingOp("PUT", "habit_logs", "l-1", mapOf("count" to 1L, "evidence" to "{}"))
        uploader(FakeApi { HttpResult(422, """{"code":"BACKDATED","opIndex":0}""") }, drafts).upload(FakeTx(3, listOf(tick)))
        assertEquals(listOf("transaction"), drafts.saved.map { it.kind }) // no text draft, but the tick itself is kept
    }

    @Test
    fun retryLaterDoesNotComplete() = runTest {
        val tx = FakeTx(4, listOf(habitOp))
        assertFailsWith<RetryLater> { uploader(FakeApi { HttpResult(503, null) }).upload(tx) }
        assertFalse(tx.completed)
        assertFailsWith<RetryLater> { uploader(FakeApi { throw IOException("offline") }).upload(tx) }
        assertFalse(tx.completed)
    }

    @Test
    fun serverBugBecomesDraftAfter24h() = runTest {
        val drafts = MemoryDrafts(); val state = MemoryState()
        val api = FakeApi { HttpResult(500, "boom") }
        val tx = FakeTx(5, listOf(journalOp))
        assertFailsWith<RetryLater> { uploader(api, drafts, state).upload(tx) } // first failure: retry
        now = now.plus(Duration.ofHours(23))
        assertFailsWith<RetryLater> { uploader(api, drafts, state).upload(tx) } // still within a day
        now = now.plus(Duration.ofHours(2))
        uploader(api, drafts, state).upload(tx) // a day on: set aside so the queue moves
        assertTrue(tx.completed)
        assertEquals(setOf("SERVER_ERROR"), drafts.saved.map { it.reason }.toSet())
        assertEquals(listOf("journal_entries.text", "transaction"), drafts.saved.map { it.kind })
    }

    @Test
    fun successAfterFailuresClearsState() = runTest {
        val state = MemoryState(); var status = 500
        val api = FakeApi { HttpResult(status, null) }
        val tx = FakeTx(6, listOf(habitOp))
        assertFailsWith<RetryLater> { uploader(api, state = state).upload(tx) }
        status = 200
        uploader(api, state = state).upload(tx)
        assertTrue(state.first.isEmpty())
    }

    @Test
    fun setAsideIsReportedSoTheUserIsTold() = runTest {
        val reports = mutableListOf<SetAside>()
        val up = Uploader(FakeApi { HttpResult(422, """{"code":"INVALID_EVIDENCE","opIndex":0}""") }, MemoryDrafts(), MemoryState(), { reports += it }) { now }
        up.upload(FakeTx(5, listOf(PendingOp("PATCH", "habit_logs", "l-1", mapOf("count" to 1L, "evidence" to "{}")))))
        up.upload(FakeTx(6, listOf(journalOp)))
        assertEquals(listOf(SetAside("INVALID_EVIDENCE", keptDraft = false), SetAside("INVALID_EVIDENCE", keptDraft = true)), reports)
    }

    @Test
    fun setAsideMessagesAreInPlainWords() {
        assertEquals(
            "the server couldn't confirm when that tick happened, so it was undone.",
            SetAside("INVALID_EVIDENCE", keptDraft = false).message(),
        )
        assertEquals(
            "that change was for a day that had already ended, so it was undone. your text is saved as a draft.",
            SetAside("BACKDATED", keptDraft = true).message(),
        )
        assertEquals("the server refused a change (BAD_OP), so it was undone.", SetAside("BAD_OP", keptDraft = false).message())
        // M8 (review): the weekly focus refusals in plain words.
        assertEquals("the focus can only change on sunday or monday, so that change was undone.", SetAside("FOCUS_CLOSED", false).message())
        assertEquals("that week already has 2 focus items, so this one was undone.", SetAside("FOCUS_FULL", false).message())
        assertEquals("that focus couldn't be saved as it was, so it was undone.", SetAside("INVALID_FOCUS", false).message())
    }

    @Test
    fun setAsideKeepsTheWholeTransaction() = runTest {
        // A tick has no typed text, but it must never vanish without a trace.
        val drafts = MemoryDrafts()
        val tick = PendingOp("PATCH", "habit_logs", "l-1", mapOf("count" to 1L, "evidence" to "{}"))
        val tx = FakeTx(7, listOf(tick))
        uploader(FakeApi { HttpResult(422, """{"code":"INVALID_EVIDENCE","opIndex":0}""") }, drafts).upload(tx)
        assertEquals(listOf(Draft("transaction", OpMapper.batch(tx).toString(), "INVALID_EVIDENCE")), drafts.saved)
    }

    @Test
    fun noticeWaitsUntilSomeoneShowsIt() {
        val notices = SetAsideNotices()
        notices.add(SetAside("INVALID_EVIDENCE", keptDraft = false)) // nobody is listening yet
        notices.add(SetAside("BACKDATED", keptDraft = true))
        assertEquals(2, notices.pending.value.size)
        notices.consume(notices.pending.value)
        assertEquals(emptyList(), notices.pending.value)
    }

    @Test
    fun accountSwitchNeverDropsUnsyncedChanges() {
        assertEquals(SwitchAction.CONNECT, accountSwitch(lastUser = null, newUser = "a", unsyncedChanges = false))
        assertEquals(SwitchAction.CONNECT, accountSwitch(lastUser = "a", newUser = "a", unsyncedChanges = true))
        assertEquals(SwitchAction.CLEAR_THEN_CONNECT, accountSwitch(lastUser = "a", newUser = "b", unsyncedChanges = false))
        assertEquals(SwitchAction.REFUSE, accountSwitch(lastUser = "a", newUser = "b", unsyncedChanges = true))
    }
}
