package app.loopdo.android.notify

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.android.sync.LoopDoSchema
import app.loopdo.shared.remind.Ping
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import app.loopdo.shared.remind.reminderKey
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Runs on the phone: the scheduler with stand-in alarms and notifications, on a real database. */
@RunWith(AndroidJUnit4::class)
class RemindersTest {
    private lateinit var db: PowerSyncDatabase
    private lateinit var store: ReminderStore
    private lateinit var scheduler: ReminderScheduler
    private var now = Instant.parse("2026-11-18T06:30:00Z") // 12:00 IST, LoopDo wed 18 nov
    private var today: LocalDate? = LocalDate.of(2026, 11, 18)
    private val d18 = LocalDate.of(2026, 11, 18)
    private val elapsed = 1_000_000L

    private val alarms = object : AlarmSetter {
        val set = mutableMapOf<String, Long>()
        val cancelled = mutableListOf<String>()
        override fun set(key: String, elapsedMs: Long) { set[key] = elapsedMs }
        override fun cancel(key: String) { set.remove(key); cancelled += key }
    }
    private val poster = object : Poster {
        var enabled = true
        val posted = mutableMapOf<Int, Shown>()
        val cancelled = mutableListOf<Int>()
        override fun post(id: Int, shown: Shown, r: Reminder): Boolean { if (!enabled) return false; posted[id] = shown; return true }
        override fun cancel(id: Int) { posted.remove(id); cancelled += id }
    }

    @Before
    fun open() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = PowerSyncDatabase(DatabaseDriverFactory(ctx), LoopDoSchema.schema, dbFilename = "test-${UUID.randomUUID()}.db")
        db.execute(
            "INSERT INTO day_settings_versions (id, effective_at, time_zone, day_end) VALUES (?, '1970-01-01T00:00:00Z', 'Asia/Kolkata', '04:00')",
            listOf(UUID.randomUUID().toString()),
        )
        db.execute("INSERT INTO journal_entries (id, loopdo_date, text) VALUES (?, '2026-11-18', 'x')", listOf(UUID.randomUUID().toString()))
        db.execute("INSERT INTO journal_entries (id, loopdo_date, text) VALUES (?, '2026-11-19', 'x')", listOf(UUID.randomUUID().toString()))
        store = ReminderStore(ctx.getSharedPreferences("test-reminders-${UUID.randomUUID()}", Context.MODE_PRIVATE))
        scheduler = ReminderScheduler(ReminderData(db), store, alarms, poster, today = { today }, trustedNow = { now }, elapsedNow = { elapsed })
    }

    @After
    fun close() = runBlocking { db.close(); app.loopdo.android.deleteTestDatabases() }

    private suspend fun habit(title: String, time: String?): String {
        val id = UUID.randomUUID().toString()
        db.execute("INSERT INTO habits (id, title, kind, target, reminder_time, step, sort_order) VALUES (?, ?, 'yes_no', 1, ?, 1, 0)", listOf(id, title, time))
        db.execute("INSERT INTO habit_schedules (id, habit_id, valid_from, recurrence) VALUES (?, ?, '2026-11-01', 'daily')", listOf(UUID.randomUUID().toString(), id))
        return id
    }
    private suspend fun tick(habit: String) = db.execute(
        "INSERT INTO habit_logs (id, habit_id, loopdo_date, count) VALUES (?, ?, '2026-11-18', 1)", listOf(UUID.randomUUID().toString(), habit),
    )
    private fun key(id: String, ping: Ping = Ping.AT, date: LocalDate = d18) = reminderKey(ReminderKind.HABIT, id, date, ping)

    @Test
    fun replanSetsOneAlarmPerReminderPlusReplan() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        // gym ×2 today + ×2 tomorrow, day end ×2 (journals are written), and the replan alarm
        assertEquals(setOf(key(gym, Ping.BEFORE), key(gym), key(gym, Ping.BEFORE, d18.plusDays(1)), key(gym, Ping.AT, d18.plusDays(1)),
            "day_end:-:2026-11-18:at", "day_end:-:2026-11-19:at", REPLAN), alarms.set.keys)
        assertEquals(elapsed + 5L * 3600_000 + 50 * 60_000, alarms.set[key(gym, Ping.BEFORE)]) // 17:50 IST is 5 h 50 min after noon
        assertEquals(7, store.scheduled().size + 1)
    }

    @Test
    fun replanCancelsRemovedKeys() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        db.execute("UPDATE habits SET reminder_time = NULL WHERE id = ?", listOf(gym))
        scheduler.replan()
        assertTrue(alarms.cancelled.containsAll(listOf(key(gym, Ping.BEFORE), key(gym))))
        assertFalse(alarms.set.keys.any { it.startsWith("habit:") })
    }

    @Test
    fun notReadyCancelsEverything() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        scheduler.fire(key(gym, Ping.BEFORE))
        assertEquals(1, poster.posted.size)
        today = null
        scheduler.replan()
        assertTrue(alarms.set.isEmpty())
        assertTrue(poster.posted.isEmpty())
        assertTrue(store.scheduled().isEmpty())
    }

    @Test
    fun fireDoneHabitPostsNothing() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        tick(gym)
        now = Instant.parse("2026-11-18T12:20:00Z") // 17:50 IST
        scheduler.fire(key(gym, Ping.BEFORE))
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun firePostsAndMarksShown() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        now = Instant.parse("2026-11-18T12:20:00Z")
        scheduler.fire(key(gym, Ping.BEFORE))
        assertEquals("gym in 10 min", poster.posted.values.single().title)
        assertTrue(key(gym, Ping.BEFORE) in store.shown())
    }

    @Test
    fun refusedPostIsNotMarkedShown() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        poster.enabled = false
        now = Instant.parse("2026-11-18T12:20:00Z")
        scheduler.fire(key(gym, Ping.BEFORE))
        assertFalse(key(gym, Ping.BEFORE) in store.shown())
    }

    @Test
    fun missedAlarmIsCaughtUpOnce() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        now = Instant.parse("2026-11-18T12:50:00Z") // 18:20 IST: both pings passed while the phone was off
        scheduler.replan()
        assertEquals(listOf("gym · now"), poster.posted.values.map { it.title })
        poster.posted.clear()
        scheduler.replan()
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun freshInstallNeverFloods() = runBlocking {
        habit("gym", "18:00")
        now = Instant.parse("2026-11-18T14:30:00Z") // 20:00 IST
        scheduler.replan()
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun doneElsewhereRemovesTheShownNotification() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        now = Instant.parse("2026-11-18T12:20:00Z")
        scheduler.fire(key(gym, Ping.BEFORE))
        tick(gym) // e.g. ticked in the app, or synced from the other device
        scheduler.replan()
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun realChannelsExist() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(CH_HABITS, CH_TASKS, CH_JOURNAL, CH_DAY_END, CH_DAY_END_NIGHT).forEach { assertNotNull(it, nm.getNotificationChannel(it)) }
        assertNull(nm.getNotificationChannel(CH_DAY_END_NIGHT).sound)
        assertNotNull(nm.getNotificationChannel(CH_DAY_END).sound)
    }
}
