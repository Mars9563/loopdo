package app.loopdo.android.tasks

import app.loopdo.client.tasks.TaskActions
import app.loopdo.client.tasks.TaskDraft
import app.loopdo.client.tasks.template

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.client.sync.LoopDoSchema
import app.loopdo.client.time.AnchorStore
import app.loopdo.client.time.DeviceClocks
import app.loopdo.client.time.SignedAnchor
import app.loopdo.client.time.TrustedClock
import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.loopDoDate
import app.loopdo.shared.task.RepeatRule
import app.loopdo.shared.task.instanceId
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import com.powersync.db.crud.CrudEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Runs on the phone: task, list and repeating-task actions on a real PowerSync database. LoopDo today = sat 14 nov 2026. */
@RunWith(AndroidJUnit4::class)
class TaskActionsTest {
    private lateinit var db: PowerSyncDatabase
    private lateinit var actions: TaskActions
    private val user = UUID.fromString("0b5c4f0e-6d1a-4c55-9d8e-3f0a1b2c3d4e")
    private val now = Instant.parse("2026-11-14T19:12:00Z") // 00:42 Sun IST = LoopDo sat 14 nov
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    private val today = LocalDate.of(2026, 11, 14)
    private fun nov(day: Int) = LocalDate.of(2026, 11, day)

    private val clocks = object : DeviceClocks {
        override fun wallClock() = now
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
        actions = TaskActions(db, { user }, { loopDoDate(it, ist) }, TrustedClock(clocks, anchors))
    }

    @After
    fun close() = runBlocking { db.close(); app.loopdo.android.deleteTestDatabases() }

    private fun draft(title: String, due: LocalDate? = null, repeat: RepeatRule? = null, listId: UUID? = null) =
        TaskDraft(title = title, notes = null, due = due, time = null, listId = listId, repeat = repeat)

    private suspend fun ops(): List<CrudEntry> = db.getCrudBatch(1000)?.crud.orEmpty()

    /** One text column of one row (null stays null). */
    private suspend fun col(sql: String, vararg args: Any?): String? = db.get(sql, args.toList()) { listOf(it.getString(0)) }.single()

    private suspend fun instances(template: UUID): List<Pair<String, String>> = db.getAll(
        "SELECT occurrence_date, title FROM tasks WHERE template_id = ? ORDER BY occurrence_date", listOf(template.toString()),
    ) { it.getString(0)!! to it.getString(1)!! }

    @Test
    fun ensureInstancesTwiceCreatesOnce() = runBlocking {
        val t = actions.saveTask(null, draft("water plants", due = nov(10), repeat = RepeatRule.Daily))
        assertEquals((10..14).map { nov(it).toString() }, instances(t).map { it.first })
        assertEquals((10..14).map { instanceId(t, nov(it)).toString() }, db.getAll("SELECT id FROM tasks WHERE template_id = ? ORDER BY occurrence_date", listOf(t.toString())) { it.getString(0)!! })
        assertEquals("2026-11-14", col("SELECT generated_through FROM tasks WHERE id = ?", t.toString()))
        assertEquals("""{"type":"daily"}""", col("SELECT repeat_rule FROM tasks WHERE id = ?", t.toString())) // the template, not a task of a day
        val before = ops().size
        actions.ensureInstances(today)
        actions.ensureInstances(today)
        assertEquals(5, instances(t).size)
        assertEquals("a no-op run writes nothing", before, ops().size)
        // The next day (another device, or the day rolling over) adds exactly that day.
        actions.ensureInstances(nov(15))
        assertEquals(6, instances(t).size)
        val added = ops().drop(before)
        assertEquals(listOf("tasks", "tasks"), added.map { it.table }) // the instance, then generated_through
        assertEquals(instanceId(t, nov(15)).toString(), added[0].id)
        assertEquals(t.toString(), added[1].id)
        assertEquals(1, added.map { it.transactionId }.distinct().size)
    }

    @Test
    fun deletedInstanceIsNotRecreated() = runBlocking {
        val t = actions.saveTask(null, draft("water plants", due = nov(10), repeat = RepeatRule.Daily))
        actions.deleteTask(instanceId(t, nov(12)))
        actions.ensureInstances(today)
        assertEquals(listOf("2026-11-10", "2026-11-11", "2026-11-13", "2026-11-14"), instances(t).map { it.first })
    }

    @Test
    fun editSeriesReplacesOnlyFutureOpenInstances() = runBlocking {
        val t = actions.saveTask(null, draft("water plants", due = nov(10), repeat = RepeatRule.Daily))
        db.execute("UPDATE tasks SET completed_at = '2026-11-11T10:00:00Z', completed_on = '2026-11-11' WHERE id = ?", listOf(instanceId(t, nov(11)).toString()))
        val next = actions.editSeries(t, draft("water the balcony", due = today, repeat = RepeatRule.Weekly(setOf(DayOfWeek.SATURDAY, DayOfWeek.WEDNESDAY))))
        // The past stays as it was: done on 11 nov, late 10/12/13 nov, all under the old name.
        assertEquals(listOf("2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13"), instances(t).map { it.first })
        assertTrue(instances(t).all { it.second == "water plants" })
        // Today's open one is replaced by the new series.
        assertEquals(listOf("2026-11-14" to "water the balcony"), instances(next))
        assertEquals("9999-12-31", col("SELECT generated_through FROM tasks WHERE id = ?", t.toString()))
        actions.ensureInstances(nov(18))
        assertEquals(listOf("2026-11-14", "2026-11-18"), instances(next).map { it.first }) // sat, then wed
        assertEquals(4, instances(t).size) // the stopped series makes nothing more

        // Today's occurrence already done: the new series doesn't add a second one today.
        val u = actions.saveTask(null, draft("stretch", due = nov(13), repeat = RepeatRule.Daily))
        db.execute("UPDATE tasks SET completed_at = '2026-11-14T10:00:00Z', completed_on = '2026-11-14' WHERE id = ?", listOf(instanceId(u, today).toString()))
        val v = actions.editSeries(u, draft("stretch 10 min", due = today, repeat = RepeatRule.Daily))
        assertEquals(emptyList<Pair<String, String>>(), instances(v))
        assertEquals(listOf("2026-11-13", "2026-11-14"), instances(u).map { it.first })
    }

    // Owner, 2026-11-18: ending a series ("never" in the series editor) keeps today's open copy.
    @Test
    fun endingASeriesInTheEditorKeepsTodaysOpenCopy() = runBlocking {
        val t = actions.saveTask(null, draft("water plants", due = nov(12), repeat = RepeatRule.Daily))
        actions.editSeries(t, draft("water the plants", due = today, repeat = null))
        assertEquals(listOf("2026-11-12", "2026-11-13", "2026-11-14"), instances(t).map { it.first })
        assertEquals("water the plants", col("SELECT title FROM tasks WHERE id = ?", instanceId(t, today).toString())) // the edit applies to it
        actions.ensureInstances(nov(20))
        assertEquals(3, instances(t).size)
    }

    @Test
    fun stopRepeatingKeepsWhatExists() = runBlocking {
        val t = actions.saveTask(null, draft("water plants", due = nov(12), repeat = RepeatRule.Daily))
        actions.stopRepeating(t)
        actions.ensureInstances(nov(20))
        assertEquals(listOf("2026-11-12", "2026-11-13", "2026-11-14"), instances(t).map { it.first })
    }

    @Test
    fun deleteListMovesTasksToInbox() = runBlocking {
        val list = actions.saveList(null, "job hunt")
        val a = actions.saveTask(null, draft("apply: 2 jobs", due = today, listId = list))
        val b = actions.saveTask(null, draft("update cv", listId = list))
        val c = actions.saveTask(null, draft("call recruiter", due = nov(12), listId = list))
        db.execute("UPDATE tasks SET completed_at = '2026-11-12T10:00:00Z', completed_on = '2026-11-12' WHERE id = ?", listOf(c.toString()))
        val before = ops().size
        actions.deleteList(list)
        assertEquals(0L, db.get("SELECT count(*) FROM lists") { it.getLong(0)!! })
        assertEquals(3L, db.get("SELECT count(*) FROM tasks WHERE list_id IS NULL AND id IN (?, ?, ?)", listOf(a, b, c).map { it.toString() }) { it.getLong(0)!! })
        val deleted = ops().drop(before)
        assertEquals("lists", deleted.last().table) // the tasks move first
        assertEquals(1, deleted.map { it.transactionId }.distinct().size)
        assertTrue(deleted.none { it.table == "tasks" && it.op.name == "DELETE" })
    }

    @Test
    fun moveLateTaskKeepsItsTitleAndList() = runBlocking {
        val list = actions.saveList(null, "home")
        val t = actions.saveTask(null, draft("fix bike brake", due = nov(12), listId = list))
        val before = ops().size
        actions.moveTask(t, nov(15), null)
        val row = db.get("SELECT title, list_id, due_date FROM tasks WHERE id = ?", listOf(t.toString())) { listOf(it.getString(0), it.getString(1), it.getString(2)) }
        assertEquals(listOf("fix bike brake", list.toString(), "2026-11-15"), row)
        val move = ops().drop(before).single()
        assertTrue(move.opData!!.containsKey("due_date"))
        assertFalse("only the date travels; the server records the late history", move.opData!!.containsKey("title"))
        actions.moveToList(t, null)
        assertNull(col("SELECT list_id FROM tasks WHERE id = ?", t.toString()))
        assertEquals("pick today or a later day.", runCatching { actions.moveTask(t, nov(13), null) }.exceptionOrNull()?.message)
    }

    @Test
    fun doneTaskKeepsItsDateAndCannotBeDeleted() = runBlocking {
        // Review of M5: re-dating a done late task would make it on time; deleting it would erase a past active day.
        val t = actions.saveTask(null, draft("fix bike brake", due = nov(12)))
        db.execute("UPDATE tasks SET completed_at = '2026-11-13T10:00:00Z', completed_on = '2026-11-13' WHERE id = ?", listOf(t.toString()))
        val before = ops().size
        assertEquals("it's done, so its date stays. reopen it first.", runCatching { actions.saveTask(t, draft("fix bike brake", due = today)) }.exceptionOrNull()?.message)
        assertEquals("it's done, so its date stays. reopen it first.", runCatching { actions.saveTask(t, draft("fix bike brake", due = null)) }.exceptionOrNull()?.message)
        assertEquals("a done task stays in your history, so it can't be deleted.", runCatching { actions.deleteTask(t) }.exceptionOrNull()?.message)
        assertEquals(before, ops().size)
        assertEquals("2026-11-12", col("SELECT due_date FROM tasks WHERE id = ?", t.toString()))
        // Its name and notes can still change.
        actions.saveTask(t, draft("fixed the bike brake", due = nov(12)))
        assertEquals("fixed the bike brake", col("SELECT title FROM tasks WHERE id = ?", t.toString()))
    }

    @Test
    fun subtasksAreOneTransactionEach() = runBlocking {
        val t = actions.saveTask(null, draft("pay rent", due = today))
        val before = ops().size
        val a = actions.addSubtask(t, "check the amount")
        val b = actions.addSubtask(t, "transfer from savings")
        actions.toggleSubtask(a)
        actions.deleteSubtask(b)
        val rows = db.getAll("SELECT title, done, sort_order FROM subtasks WHERE task_id = ?", listOf(t.toString())) { listOf(it.getString(0), it.getLong(1).toString(), it.getLong(2).toString()) }
        assertEquals(listOf(listOf("check the amount", "1", "0")), rows)
        val changes = ops().drop(before)
        assertEquals(4, changes.map { it.transactionId }.distinct().size)
        assertTrue(changes.all { it.table == "subtasks" })
    }

    @Test
    fun repeatingInstancesCopyTheChecklist() = runBlocking {
        val t = actions.saveTask(null, draft("pay rent", due = nov(20), repeat = RepeatRule.MonthlyOnDay(1)))
        actions.addSubtask(t, "check the amount")
        actions.ensureInstances(LocalDate.of(2026, 12, 1))
        val i = instanceId(t, LocalDate.of(2026, 12, 1))
        assertEquals(listOf("check the amount"), db.getAll("SELECT title FROM subtasks WHERE task_id = ?", listOf(i.toString())) { it.getString(0)!! })
        assertEquals(listOf("2026-12-01"), instances(t).map { it.first })
    }

    // M6.5 (M5 item 4): a move carries proof of the day it was made, so an on-time move made offline isn't late.
    @Test
    fun aMoveCarriesProofOfItsDay() = runBlocking {
        val withProof = TaskActions(db, { user }, { loopDoDate(it, ist) }, TrustedClock(clocks, anchors), app.loopdo.client.time.EvidenceFactory(clocks, anchors))
        val t = withProof.saveTask(null, draft("call home", due = today))
        withProof.moveTask(t, nov(20), null)
        val move = ops().last { it.table == "tasks" }
        assertTrue("the move PATCH has the evidence", move.opData!!["evidence"].toString().contains("2026-11-14"))
        // Review of M6.5: a re-date in the editor carries its proof in the SAME change as the new date.
        val before = ops().size
        withProof.saveTask(t, draft("call home", due = nov(21)))
        val edit = ops().drop(before).filter { it.table == "tasks" }
        assertEquals(1, edit.size)
        assertEquals("2026-11-21", edit.single().opData!!["due_date"])
        assertTrue("the editor's PATCH has the evidence", edit.single().opData!!["evidence"].toString().contains("2026-11-14"))
    }
}
