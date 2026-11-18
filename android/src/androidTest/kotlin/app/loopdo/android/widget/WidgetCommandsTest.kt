package app.loopdo.android.widget

import app.loopdo.client.notify.TapKind
import app.loopdo.client.notify.TapResult
import app.loopdo.client.notify.WidgetTap

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.client.sync.LoopDoSchema
import app.loopdo.client.time.AnchorStore
import app.loopdo.client.time.DeviceClocks
import app.loopdo.client.time.EvidenceFactory
import app.loopdo.client.time.SignedAnchor
import app.loopdo.client.time.TrustedClock
import app.loopdo.client.today.Actions
import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.loopDoDate
import app.loopdo.shared.task.instanceId
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Runs on the phone: what a widget tap does, against a real PowerSync database and a fixed clock. */
@RunWith(AndroidJUnit4::class)
class WidgetCommandsTest {
    private lateinit var db: PowerSyncDatabase
    private lateinit var commands: WidgetCommands
    private var redraws = 0
    private var readyDay: LocalDate? = LocalDate.of(2026, 11, 14)
    private val user = UUID.fromString("0b5c4f0e-6d1a-4c55-9d8e-3f0a1b2c3d4e")
    private val now = Instant.parse("2026-11-14T19:12:00Z") // LoopDo sat 14 nov
    private val today = LocalDate.of(2026, 11, 14)
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
    private val clocks = object : DeviceClocks {
        private var elapsed = 1_000_000L
        override fun wallClock() = now
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
        val actions = Actions(db, TrustedClock(clocks, anchors), EvidenceFactory(clocks, anchors), { loopDoDate(it, ist) }, { user })
        commands = WidgetCommands(actions, readyOn = { readyDay }) { redraws++ }
    }

    @After
    fun close() = runBlocking { db.close(); app.loopdo.android.deleteTestDatabases() }

    private suspend fun habit(kind: String, target: Int): UUID {
        val id = UUID.randomUUID()
        db.execute("INSERT INTO habits (id, title, kind, target, sort_order) VALUES (?, 'h', ?, ?, 0)", listOf(id.toString(), kind, target))
        db.execute(
            "INSERT INTO habit_schedules (id, habit_id, valid_from, recurrence) VALUES (?, ?, '2026-11-01', 'daily')",
            listOf(UUID.randomUUID().toString(), id.toString()),
        )
        return id
    }

    private suspend fun count(h: UUID) = db.getOptional("SELECT count FROM habit_logs WHERE id = ?", listOf(instanceId(h, today).toString())) { it.getLong(0)!! }

    @Test
    fun widgetTickWritesLikeTheApp() = runBlocking {
        val h = habit("yes_no", 1)
        commands.tickHabit(h, today)
        assertEquals(1L, count(h))
        val evidence = db.get("SELECT evidence FROM habit_logs WHERE id = ?", listOf(instanceId(h, today).toString())) { it.getString(0).orEmpty() }
        assertNotNull(evidence.takeIf { "\"claimedDate\":\"2026-11-14\"" in it })
        assertEquals(1, redraws)
    }

    @Test
    fun staleWidgetTapRedrawsAndWritesNothing() = runBlocking {
        val h = habit("yes_no", 1)
        commands.tickHabit(h, today.minusDays(1)) // the widget still shows fri 13
        assertEquals(null, count(h))
        assertEquals(1, redraws) // the redraw is how a stale widget heals
    }

    @Test
    fun handleReportsStale() = runBlocking {
        val h = habit("yes_no", 1)
        val yesterday = today.minusDays(1)
        assertEquals(TapResult.STALE, commands.handle(WidgetTap(TapKind.TICK, h.toString(), yesterday, done = false)))
        assertEquals(null, count(h))
        assertEquals(TapResult.DONE, commands.handle(WidgetTap(TapKind.TICK, h.toString(), today, done = false)))
        assertEquals(1L, count(h))
    }

    @Test
    fun plusOneTwiceCountsTwo() = runBlocking {
        val h = habit("count", 8)
        commands.plus(h, today, 1)
        commands.plus(h, today, 1)
        assertEquals(2L, count(h))
        assertEquals(2, db.getCrudBatch(100)!!.crud.filter { it.table == "habit_logs" }.map { it.transactionId }.distinct().size)
    }
}
