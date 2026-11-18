package app.loopdo.android.today

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.android.sync.LoopDoSchema
import app.loopdo.android.time.AnchorStore
import app.loopdo.android.time.DeviceClocks
import app.loopdo.android.time.EvidenceFactory
import app.loopdo.android.time.SignedAnchor
import app.loopdo.android.time.TrustedClock
import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.loopDoDate
import app.loopdo.shared.task.instanceId
import app.loopdo.shared.task.uuidV5
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import com.powersync.db.crud.CrudEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Runs on the phone: the today actions against a real PowerSync database and a fixed clock. */
@RunWith(AndroidJUnit4::class)
class ActionsTest {
    private lateinit var db: PowerSyncDatabase
    private lateinit var actions: Actions
    private val user = UUID.fromString("0b5c4f0e-6d1a-4c55-9d8e-3f0a1b2c3d4e")
    private var now = Instant.parse("2026-11-14T19:12:00Z") // 00:42 Sun IST = LoopDo sat 14 nov
    private val today = LocalDate.of(2026, 11, 14)
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))

    private val clocks = object : DeviceClocks {
        override fun wallClock() = now
        // Like the real timer it only moves forward, so no two ticks carry identical evidence.
        private var elapsed = 1_000_000L
        override fun elapsedRealtimeMs() = elapsed++
        override fun bootCount() = 3
        override fun networkTime() = now
    }
    private val anchors = object : AnchorStore {
        override fun current(): SignedAnchor? = null
        override fun save(a: SignedAnchor) = Unit
    }

    @Before
    fun open() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = PowerSyncDatabase(DatabaseDriverFactory(ctx), LoopDoSchema.schema, dbFilename = "test-${UUID.randomUUID()}.db")
        actions = Actions(db, TrustedClock(clocks, anchors), EvidenceFactory(clocks, anchors), { loopDoDate(it, ist) }, { user })
    }

    @After
    fun close() = runBlocking { db.close() }

    /** A habit due every day since 1 nov (or only on [weekdays], ISO numbers, e.g. "[1]" = mondays). */
    private suspend fun habit(kind: String, target: Int, weekdays: String? = null): UUID {
        val id = UUID.randomUUID()
        db.execute("INSERT INTO habits (id, title, kind, target, sort_order) VALUES (?, ?, ?, ?, 0)", listOf(id.toString(), "h", kind, target))
        db.execute(
            "INSERT INTO habit_schedules (id, habit_id, valid_from, recurrence, weekdays) VALUES (?, ?, '2026-11-01', ?, ?)",
            listOf(UUID.randomUUID().toString(), id.toString(), if (weekdays == null) "daily" else "weekdays", weekdays),
        )
        return id
    }

    private suspend fun ops(): List<CrudEntry> = db.getCrudBatch(1000)?.crud.orEmpty()

    @Test
    fun tickWritesDeterministicLogWithEvidence() = runBlocking {
        val h = habit("yes_no", 1)
        val before = ops().size
        actions.tickHabit(h)
        val id = instanceId(h, today).toString()
        val row = db.get("SELECT count, loopdo_date, evidence, habit_id FROM habit_logs WHERE id = ?", listOf(id)) {
            listOf(it.getLong(0).toString(), it.getString(1), it.getString(2), it.getString(3))
        }
        assertEquals(listOf("1", "2026-11-14"), row.take(2))
        assertEquals(h.toString(), row[3])
        val ev = Json.parseToJsonElement(row[2]!!).jsonObject
        assertEquals("2026-11-14", ev["claimedDate"]!!.jsonPrimitive.content)
        assertEquals(now.toString(), ev["networkTime"]!!.jsonPrimitive.content)
        val tickOps = ops().drop(before)
        assertEquals(1, tickOps.size) // one row, one transaction
        assertEquals("habit_logs", tickOps.single().table)
        assertEquals(id, tickOps.single().id)
    }

    @Test
    fun secondTickOnCountHabitIncrements() = runBlocking {
        val h = habit("count", 8)
        actions.tickHabit(h)
        actions.tickHabit(h)
        val id = instanceId(h, today).toString()
        assertEquals(2L, db.get("SELECT count FROM habit_logs WHERE id = ?", listOf(id)) { it.getLong(0)!! })
        val logOps = ops().filter { it.table == "habit_logs" }
        assertEquals(listOf(id, id), logOps.map { it.id })
        assertEquals(2, logOps.map { it.transactionId }.distinct().size) // each tick is its own transaction
        assertTrue("the PATCH carries fresh evidence", logOps[1].opData!!.containsKey("evidence"))
        actions.decrementHabit(h)
        actions.decrementHabit(h)
        actions.decrementHabit(h) // floored at 0
        assertEquals(0L, db.get("SELECT count FROM habit_logs WHERE id = ?", listOf(id)) { it.getLong(0)!! })
    }

    @Test
    fun journalLineAppendsToDeterministicEntry() = runBlocking {
        actions.appendJournalLine("pizza was good")
        actions.appendJournalLine("  long walk  ")
        val id = uuidV5(user, "journal:2026-11-14").toString()
        val (text, date) = db.get("SELECT text, loopdo_date FROM journal_entries WHERE id = ?", listOf(id)) { it.getString(0) to it.getString(1) }
        assertEquals("pizza was good\nlong walk", text)
        assertEquals("2026-11-14", date)
        assertEquals(1L, db.get("SELECT count(*) FROM journal_entries") { it.getLong(0)!! })
    }

    @Test
    fun countChangesCarryTheOldCountAndARetryKey() = runBlocking {
        // M6.5: the server adds the difference (new - old), once per key, so two devices' taps add up.
        val h = habit("count", 8)
        actions.tickHabit(h)
        actions.tickHabit(h)
        val logOps = ops().filter { it.table == "habit_logs" }
        assertEquals("3 -> 4 style: the PATCH knows the count before it", "1", logOps[1].previousValues!!["count"]?.toString())
        val keys = logOps.map { it.metadata }
        assertTrue("every change has a key", keys.all { !it.isNullOrBlank() })
        assertEquals("keys are unique", 2, keys.distinct().size)
    }

    @Test
    fun reopenUndatedTaskHasNoEvidence() = runBlocking {
        val t = actions.addTask("fix bike brake", due = null, time = null, listId = null)
        actions.completeTask(t)
        assertEquals(listOf("2026-11-14"), db.get("SELECT completed_on FROM tasks WHERE id = ?", listOf(t.toString())) { listOf(it.getString(0)) })
        actions.reopenTask(t)
        val (at, on) = db.get("SELECT completed_at, completed_on FROM tasks WHERE id = ?", listOf(t.toString())) { it.getString(0) to it.getString(1) }
        assertNull(at)
        assertNull(on)
        val reopen = ops().last()
        assertEquals("tasks", reopen.table)
        assertNull("no evidence on reopening an undated task", reopen.opData?.get("evidence"))
        val complete = ops().filter { it.table == "tasks" }[1]
        assertTrue("completing carries evidence", complete.opData!!["evidence"] != null)
    }

    @Test
    fun staleShownDateIsRefused() = runBlocking {
        val h = habit("yes_no", 1)
        val e = runCatching { actions.tickHabit(h, shownDate = LocalDate.of(2026, 11, 13)) }.exceptionOrNull()
        assertTrue("expected StaleDayException, got $e", e is StaleDayException)
        assertEquals(today, (e as StaleDayException).today)
        assertEquals(0L, db.get("SELECT count(*) FROM habit_logs") { it.getLong(0)!! })
    }

    @Test
    fun notDueHabitIsNotTicked() = runBlocking {
        val mondaysOnly = habit("yes_no", 1, weekdays = "[1]") // today is a saturday
        assertNull(actions.tickHabit(mondaysOnly, shownDate = today))
        assertEquals(0L, db.get("SELECT count(*) FROM habit_logs") { it.getLong(0)!! })
    }

    @Test
    fun saveJournalWritesTheShownDate() = runBlocking {
        now = Instant.parse("2026-11-15T04:30:00Z") // 10:00 sun IST: the pop-up was opened on sat 14
        actions.saveJournal(LocalDate.of(2026, 11, 14), "late night", 4)
        val id = uuidV5(user, "journal:2026-11-14").toString()
        val (text, mood) = db.get("SELECT text, mood FROM journal_entries WHERE id = ?", listOf(id)) { it.getString(0) to it.getLong(1) }
        assertEquals("late night", text)
        assertEquals(4L, mood)
        val future = runCatching { actions.saveJournal(LocalDate.of(2026, 11, 16), "x", null) }.exceptionOrNull()
        assertTrue("a day that hasn't started is refused, got $future", future is IllegalArgumentException)
    }

    @Test
    fun saveJournalMergesTextAddedSinceTheDraftStarted() = runBlocking {
        val sat = LocalDate.of(2026, 11, 14)
        actions.saveJournal(sat, "walk", null)
        actions.appendJournalLine("called mom") // e.g. [+ add] while the journal pop-up held a draft
        actions.saveJournal(sat, "walk and gym", 4, base = "walk")
        val id = uuidV5(user, "journal:2026-11-14").toString()
        assertEquals("walk\ncalled mom\nand gym", db.get("SELECT text FROM journal_entries WHERE id = ?", listOf(id)) { it.getString(0)!! })
    }

    @Test
    fun setCountAndCompleteAreOneTransactionWithEvidence() = runBlocking {
        val h = habit("count", 35) // "japanese vocab", 35 words
        val before = ops().size
        actions.tickHabit(h, today, by = 5)
        actions.setHabitCount(h, 20, today)
        actions.completeHabit(h, today)
        val id = instanceId(h, today).toString()
        assertEquals(35L, db.get("SELECT count FROM habit_logs WHERE id = ?", listOf(id)) { it.getLong(0)!! })
        val logOps = ops().drop(before).filter { it.table == "habit_logs" }
        assertEquals(3, logOps.map { it.transactionId }.distinct().size) // one transaction each
        assertTrue(logOps.all { it.opData?.get("evidence") != null }) // every count change carries its own evidence
    }

    @Test
    fun completeUsesTheTargetOfToday() = runBlocking {
        // kanji raised to 60 from today (a version made earlier): "all done" means 60, not the habit's old 50.
        val h = habit("count", 50)
        db.execute(
            "INSERT INTO habit_schedules (id, habit_id, valid_from, recurrence, target) VALUES (?, ?, ?, 'daily', 60)",
            listOf(UUID.randomUUID().toString(), h.toString(), today.toString()),
        )
        actions.completeHabit(h, today)
        assertEquals(60L, db.get("SELECT count FROM habit_logs WHERE id = ?", listOf(instanceId(h, today).toString())) { it.getLong(0)!! })
    }
}

