package app.loopdo.android.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.android.sync.LoopDoSchema
import app.loopdo.android.time.AnchorStore
import app.loopdo.android.time.DeviceClocks
import app.loopdo.android.time.EvidenceFactory
import app.loopdo.android.time.SignedAnchor
import app.loopdo.shared.stats.FocusKind
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Runs on the phone: setting the weekly focus (M8) against a real PowerSync database. */
@RunWith(AndroidJUnit4::class)
class FocusActionsTest {
    private lateinit var db: PowerSyncDatabase
    private lateinit var focus: FocusActions
    private var today = LocalDate.of(2026, 11, 22) // sunday: next week's focus is open
    private val nextMon = LocalDate.of(2026, 11, 23)
    private val user = "0b5c4f0e-6d1a-4c55-9d8e-3f0a1b2c3d4e"
    private val clocks = object : DeviceClocks {
        override fun wallClock() = Instant.parse("2026-11-22T14:30:00Z")
        private var elapsed = 1_000_000L
        override fun elapsedRealtimeMs() = elapsed++
        override fun bootCount() = 3
        override fun networkTime(): Instant? = null
    }
    private val anchors = object : AnchorStore {
        override fun current(): SignedAnchor? = null
        override fun save(a: SignedAnchor) = Unit
    }

    @Before
    fun open() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = PowerSyncDatabase(DatabaseDriverFactory(ctx), LoopDoSchema.schema, dbFilename = "test-${UUID.randomUUID()}.db")
        db.execute("INSERT INTO day_settings_versions (id, effective_at, time_zone, day_end) VALUES (?, '1970-01-01T00:00:00Z', 'Asia/Kolkata', '04:00')", listOf(UUID.randomUUID().toString()))
        focus = FocusActions(db, { user }, { today }, EvidenceFactory(clocks, anchors))
    }

    @After
    fun close() = runBlocking { db.close(); app.loopdo.android.deleteTestDatabases() }

    private suspend fun habit(title: String, order: Int = 0): String {
        val id = UUID.randomUUID().toString()
        db.execute("INSERT INTO habits (id, title, kind, target, sort_order) VALUES (?, ?, 'yes_no', 1, ?)", listOf(id, title, order))
        db.execute("INSERT INTO habit_schedules (id, habit_id, valid_from, recurrence) VALUES (?, ?, '2026-11-01', 'daily')", listOf(UUID.randomUUID().toString(), id))
        return id
    }
    private suspend fun live() = db.getAll("SELECT title FROM weekly_focus WHERE week_start = ? AND deleted_at IS NULL ORDER BY sort_order", listOf(nextMon.toString())) { it.getString(0)!! }

    @Test
    fun addInWindowWritesRowWithEvidence() = runBlocking {
        val gym = habit("gym")
        val id = focus.add(nextMon, FocusKind.HABIT, gym, "gym", goal = 4, deadline = null).getOrThrow()
        val row = db.get("SELECT week_start, kind, item_id, goal, evidence, user_id FROM weekly_focus WHERE id = ?", listOf(id)) {
            listOf(it.getString(0), it.getString(1), it.getString(2), it.getLong(3)?.toString(), it.getString(4), it.getString(5))
        }
        assertEquals(listOf("2026-11-23", "habit", gym, "4"), row.take(4))
        assertTrue(row[4]!!.contains("\"claimedDate\":\"2026-11-22\""))
        assertEquals(user, row[5])
    }

    @Test
    fun addOutsideWindowRefused() = runBlocking {
        today = LocalDate.of(2026, 11, 18) // wednesday
        val r = focus.add(nextMon, FocusKind.HABIT, habit("gym"), "gym", 4, null)
        assertEquals("the focus can be set on sunday or monday.", r.exceptionOrNull()?.message)
        assertEquals(emptyList<String>(), live())
    }

    @Test
    fun thirdRefused() = runBlocking {
        focus.add(nextMon, FocusKind.HABIT, habit("a"), "a", 3, null).getOrThrow()
        focus.add(nextMon, FocusKind.HABIT, habit("b"), "b", 3, null).getOrThrow()
        assertEquals("pick at most 2.", focus.add(nextMon, FocusKind.HABIT, habit("c"), "c", 3, null).exceptionOrNull()?.message)
        assertEquals(listOf("a", "b"), live())
    }

    @Test
    fun removeSetsDeletedAt() = runBlocking {
        val id = focus.add(nextMon, FocusKind.HABIT, habit("gym"), "gym", 4, null).getOrThrow()
        focus.remove(id).getOrThrow()
        assertEquals(emptyList<String>(), live())
        assertNotNull(db.get("SELECT deleted_at FROM weekly_focus WHERE id = ?", listOf(id)) { it.getString(0).orEmpty() }.ifEmpty { null })
        today = LocalDate.of(2026, 11, 24)
        assertEquals("the focus can be set on sunday or monday.", focus.setGoal(id, 2).exceptionOrNull()?.message)
    }

    @Test
    fun rowsBuildWeekInputAndMoods() = runBlocking {
        val gym = habit("gym")
        db.execute("INSERT INTO habit_logs (id, habit_id, loopdo_date, count) VALUES (?, ?, '2026-11-20', 1)", listOf(UUID.randomUUID().toString(), gym))
        db.execute("INSERT INTO journal_entries (id, loopdo_date, text, mood) VALUES (?, '2026-11-20', '', 4)", listOf(UUID.randomUUID().toString()))
        db.execute("INSERT INTO tasks (id, title, due_date, sort_order) VALUES (?, 'pay rent', '2026-11-19', 0)", listOf(UUID.randomUUID().toString()))
        db.execute("INSERT INTO tasks (id, title, due_date, repeat_rule, sort_order) VALUES (?, 'series', '2026-11-01', '{\"type\":\"daily\"}', 0)", listOf(UUID.randomUUID().toString()))
        focus.add(nextMon, FocusKind.HABIT, gym, "gym", 4, null).getOrThrow()
        val rows = StatsData(db).rows().first()
        assertEquals(listOf("gym"), rows.input.habits.map { it.title })
        assertEquals(1, rows.input.logs.getValue(gym).single().count)
        assertEquals(listOf("pay rent"), rows.input.tasks.map { it.title }) // a series template is not a task
        assertEquals(mapOf(LocalDate.of(2026, 11, 20) to 4), rows.moods)
        assertEquals(listOf("gym"), rows.focus.map { it.title })
        assertEquals(1, rows.versions.size)
    }
}
