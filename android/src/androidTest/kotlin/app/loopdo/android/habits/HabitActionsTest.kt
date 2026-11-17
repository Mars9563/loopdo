package app.loopdo.android.habits

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
import app.loopdo.shared.habit.DayOutcome
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.habit.habitOutcomes
import app.loopdo.shared.habit.targetOn
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import com.powersync.db.crud.CrudEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Runs on the phone: the habit actions against a real PowerSync database and a fixed clock.
 * LoopDo today = sat 14 nov 2026, so "tomorrow" (the earliest a change may start) = 15 nov.
 */
@RunWith(AndroidJUnit4::class)
class HabitActionsTest {
    private lateinit var db: PowerSyncDatabase
    private lateinit var actions: HabitActions
    private val user = UUID.fromString("0b5c4f0e-6d1a-4c55-9d8e-3f0a1b2c3d4e")
    private val now = Instant.parse("2026-11-14T19:12:00Z") // 00:42 Sun IST = LoopDo sat 14 nov
    private val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
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
        actions = HabitActions(db, TrustedClock(clocks, anchors), EvidenceFactory(clocks, anchors), { loopDoDate(it, ist) }, { user })
    }

    @After
    fun close() = runBlocking { db.close() }

    private fun vocab(target: Int = 35) = HabitDraft(
        title = "japanese vocab", kind = HabitKind.COUNT, target = target, unit = "words", step = 5,
        recurrence = Recurrence.DAILY, weekdays = emptySet(), reminderTime = null,
    )

    /**
     * A habit written as fixtures (as if synced): daily since 1 nov, plus [extra] versions as
     * "yyyy-mm-dd:recurrence[:target]".
     */
    private suspend fun fixture(vararg extra: String, kind: String = "count", target: Int = 50): UUID {
        val id = UUID.randomUUID()
        db.execute("INSERT INTO habits (id, user_id, title, kind, target, step, sort_order) VALUES (?, ?, 'kanji', ?, ?, 1, 0)", listOf(id.toString(), user.toString(), kind, target))
        (listOf("2026-11-01:daily") + extra).forEach { v ->
            val p = v.split(":")
            db.execute(
                "INSERT INTO habit_schedules (id, user_id, habit_id, valid_from, recurrence, target) VALUES (?, ?, ?, ?, ?, ?)",
                listOf(UUID.randomUUID().toString(), user.toString(), id.toString(), p[0], p[1], p.getOrNull(2)?.toInt()),
            )
        }
        return id
    }

    private suspend fun log(habit: UUID, date: LocalDate, count: Int) = db.execute(
        "INSERT INTO habit_logs (id, user_id, habit_id, loopdo_date, count) VALUES (?, ?, ?, ?, ?)",
        listOf(UUID.randomUUID().toString(), user.toString(), habit.toString(), date.toString(), count),
    )

    /** "valid_from:recurrence" (+ ":target" when the version has its own target), in date order. */
    private suspend fun versions(h: UUID): List<String> = db.getAll(
        "SELECT valid_from, recurrence, target FROM habit_schedules WHERE habit_id = ? ORDER BY valid_from", listOf(h.toString()),
    ) { c -> listOfNotNull(c.getString(0), c.getString(1), c.getLong(2)?.toString()).joinToString(":") }

    private suspend fun schedules(h: UUID): List<HabitSchedule> = db.getAll(
        "SELECT valid_from, recurrence, target FROM habit_schedules WHERE habit_id = ?", listOf(h.toString()),
    ) { c -> HabitSchedule(LocalDate.parse(c.getString(0)!!), Recurrence.valueOf(c.getString(1)!!.uppercase()), target = c.getLong(2)?.toInt()) }

    /** One text column of one row (null stays null). */
    private suspend fun col(sql: String, vararg args: Any?): String? = db.get(sql, args.toList()) { listOf(it.getString(0)) }.single()

    private suspend fun ops(): List<CrudEntry> = db.getCrudBatch(1000)?.crud.orEmpty()

    private inline fun <reified E : Throwable> assertThrows(block: () -> Unit) {
        try { block() } catch (e: Throwable) { if (e is E) return; throw e }
        fail("expected ${E::class.simpleName}")
    }

    @Test
    fun createHabitStartsToday() = runBlocking {
        val before = ops().size
        val h = actions.createHabit(vocab())
        val row = db.get("SELECT title, kind, target, step, unit, user_id FROM habits WHERE id = ?", listOf(h.toString())) {
            listOf(it.getString(0), it.getString(1), it.getLong(2).toString(), it.getLong(3).toString(), it.getString(4), it.getString(5))
        }
        assertEquals(listOf("japanese vocab", "count", "35", "5", "words", user.toString()), row)
        assertEquals(listOf("2026-11-14:daily"), versions(h))
        val ev = col("SELECT evidence FROM habit_schedules WHERE habit_id = ?", h.toString())
        assertEquals("2026-11-14", Json.parseToJsonElement(ev!!).jsonObject["claimedDate"]!!.jsonPrimitive.content)
        val created = ops().drop(before)
        assertEquals(listOf("habits", "habit_schedules"), created.map { it.table }) // the habit first (foreign key)
        assertEquals(1, created.map { it.transactionId }.distinct().size)
    }

    @Test
    fun targetChangeStartsTomorrow() = runBlocking {
        val h = fixture(target = 50)
        log(h, nov(14), 20)
        val draft = vocab(target = 60).copy(title = "kanji", unit = null, step = 1)
        actions.editHabit(h, draft) // 23:50-style edit: today stays at 50
        assertEquals(listOf("2026-11-01:daily", "2026-11-15:daily:60"), versions(h))
        val habit = Habit(h.toString(), HabitKind.COUNT, 50, schedules(h))
        assertEquals(50, targetOn(habit, nov(14)))
        assertEquals(60, targetOn(habit, nov(15)))
        actions.editHabit(h, draft.copy(target = 70)) // a pending version is replaced, not stacked
        assertEquals(listOf("2026-11-01:daily", "2026-11-15:daily:70"), versions(h))
        actions.editHabit(h, draft.copy(target = 50)) // back to today's: nothing pending
        assertEquals(listOf("2026-11-01:daily"), versions(h))
    }

    @Test
    fun editDuringPauseAppliesAfterIt() = runBlocking {
        // A running pause (10–19 nov): the edit waits for the resume version.
        val running = fixture("2026-11-10:paused", "2026-11-20:daily")
        actions.editHabit(running, vocab(target = 60).copy(title = "kanji"))
        assertEquals(listOf("2026-11-01:daily", "2026-11-10:paused", "2026-11-20:daily:60"), versions(running))
        // A pause that hasn't started (18–19 nov): the edit applies from tomorrow AND after the pause.
        val pending = fixture(target = 50)
        actions.pauseHabit(pending, nov(18), nov(19))
        actions.editHabit(pending, vocab(target = 60).copy(title = "kanji"))
        assertEquals(listOf("2026-11-01:daily", "2026-11-15:daily:60", "2026-11-18:paused", "2026-11-20:daily:60"), versions(pending))
    }

    @Test
    fun endPauseResumesTomorrow() = runBlocking {
        val h = fixture("2026-11-10:paused", "2026-11-20:daily:55")
        actions.endPause(h)
        assertEquals(listOf("2026-11-01:daily", "2026-11-10:paused", "2026-11-15:daily:55"), versions(h))
        assertNotNull(col("SELECT evidence FROM habit_schedules WHERE habit_id = ? AND valid_from = '2026-11-15'", h.toString()))
    }

    @Test
    fun cancelPauseBeforeItStarts() = runBlocking {
        val h = fixture(target = 50)
        assertThrows<IllegalArgumentException> { runBlocking { actions.pauseHabit(h, nov(14), nov(16)) } } // not today
        actions.pauseHabit(h, nov(18), nov(19))
        assertEquals(listOf("2026-11-01:daily", "2026-11-18:paused", "2026-11-20:daily"), versions(h))
        assertThrows<IllegalStateException> { runBlocking { actions.pauseHabit(h, nov(25), nov(26)) } } // one pause at a time
        actions.cancelPause(h)
        assertEquals(listOf("2026-11-01:daily"), versions(h))
        // A running pause can't be cancelled (its days are history); it can be ended.
        val running = fixture("2026-11-10:paused", "2026-11-20:daily")
        assertThrows<IllegalStateException> { runBlocking { actions.cancelPause(running) } }
    }

    @Test
    fun archiveFromTomorrowAndRestoreKeepsPastNeutral() = runBlocking {
        val h = fixture(kind = "yes_no", target = 1)
        actions.archiveHabit(h)
        assertEquals("2026-11-15", col("SELECT archived_on FROM habits WHERE id = ?", h.toString()))
        actions.restoreHabit(h) // archive not in force yet: simply undone
        assertEquals(listOf("2026-11-01:daily"), versions(h))
        assertNull(col("SELECT archived_on FROM habits WHERE id = ?", h.toString()))

        db.execute("UPDATE habits SET archived_on = '2026-11-10' WHERE id = ?", listOf(h.toString())) // archived since 10 nov
        val before = ops().size
        actions.restoreHabit(h)
        assertEquals(listOf("2026-11-01:daily", "2026-11-10:paused", "2026-11-15:daily"), versions(h))
        assertNull(col("SELECT archived_on FROM habits WHERE id = ?", h.toString()))
        val restore = ops().drop(before)
        assertEquals(listOf("habit_schedules", "habit_schedules", "habits"), restore.map { it.table }) // the server needs archived_on until the pause is in
        assertEquals(1, restore.map { it.transactionId }.distinct().size)
        val outcomes = habitOutcomes(Habit(h.toString(), HabitKind.YES_NO, 1, schedules(h)), emptyList(), nov(14)).toMap()
        (10..14).forEach { assertEquals("day $it", DayOutcome.NEUTRAL, outcomes[nov(it)]) }
        assertEquals(DayOutcome.MISS, outcomes[nov(9)]) // a day before the archive keeps its outcome
    }

    @Test
    fun hideKeepsHistory() = runBlocking {
        val h = fixture(kind = "yes_no", target = 1)
        log(h, nov(12), 1)
        actions.hideHabit(h)
        assertNotNull(col("SELECT deleted_at FROM habits WHERE id = ?", h.toString()))
        assertEquals(1L, db.get("SELECT count(*) FROM habit_logs WHERE habit_id = ?", listOf(h.toString())) { it.getLong(0)!! })
        assertEquals(listOf("2026-11-01:daily"), versions(h))
        assertTrue("never a DELETE", ops().none { it.op.name == "DELETE" })
    }

    @Test
    fun reorderIsOneTransaction() = runBlocking {
        val a = fixture(); val b = fixture(); val c = fixture()
        val before = ops().size
        actions.reorderHabits(listOf(c, a, b))
        val order = db.getAll("SELECT id FROM habits ORDER BY sort_order") { UUID.fromString(it.getString(0)) }
        assertEquals(listOf(c, a, b), order)
        val moved = ops().drop(before)
        assertTrue(moved.isNotEmpty())
        assertEquals(1, moved.map { it.transactionId }.distinct().size)
    }

    @Test
    fun kindCannotChangeWithHistory() = runBlocking {
        val h = fixture(kind = "yes_no", target = 1)
        log(h, nov(12), 1)
        val asCount = vocab(target = 8).copy(title = "kanji")
        assertThrows<IllegalStateException> { runBlocking { actions.editHabit(h, asCount) } }
        assertEquals("yes_no", col("SELECT kind FROM habits WHERE id = ?", h.toString()))
        assertEquals(listOf("2026-11-01:daily"), versions(h))
        // Without history the type is still free (a habit made by mistake).
        val fresh = actions.createHabit(asCount.copy(kind = HabitKind.YES_NO, target = 1))
        actions.editHabit(fresh, asCount)
        assertEquals(listOf("count", "8"), db.get("SELECT kind, target FROM habits WHERE id = ?", listOf(fresh.toString())) { listOf(it.getString(0), it.getLong(1).toString()) })
        assertEquals(listOf("2026-11-14:daily"), versions(fresh))
    }

    @Test
    fun nameStepUnitAndReminderChangeInPlace() = runBlocking {
        val h = actions.createHabit(vocab())
        actions.editHabit(h, vocab().copy(title = "vocab", step = 10, unit = "cards", reminderTime = LocalTime.of(21, 0)))
        val row = db.get("SELECT title, step, unit, reminder_time FROM habits WHERE id = ?", listOf(h.toString())) {
            listOf(it.getString(0), it.getLong(1).toString(), it.getString(2), it.getString(3))
        }
        assertEquals(listOf("vocab", "10", "cards", "21:00"), row)
        assertEquals(listOf("2026-11-14:daily"), versions(h)) // no new version for display-only changes
        actions.editHabit(h, vocab().copy(recurrence = Recurrence.WEEKDAYS, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        assertEquals("[1,5]", col("SELECT weekdays FROM habit_schedules WHERE habit_id = ? AND valid_from = '2026-11-15'", h.toString()))
    }
}
