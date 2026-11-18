package app.loopdo.android.notify

import app.loopdo.client.notify.CH_DAY_END
import app.loopdo.client.notify.CH_DAY_END_NIGHT
import app.loopdo.client.notify.CH_HABITS
import app.loopdo.client.notify.CH_JOURNAL
import app.loopdo.client.notify.CH_TASKS
import app.loopdo.client.notify.DAY_ENDED
import app.loopdo.client.notify.ItemState
import app.loopdo.client.notify.ReminderData
import app.loopdo.client.notify.Shown

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.client.sync.LoopDoSchema
import app.loopdo.android.widget.TapKind
import app.loopdo.android.widget.TapResult
import app.loopdo.android.widget.WidgetTap
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
    private val taps = mutableListOf<WidgetTap>()
    private var signedOut = true
    private var weekOpened = false
    private val poster = object : Poster {
        var enabled = true
        val posted = mutableMapOf<Int, Shown>()
        val cancelled = mutableListOf<Int>()
        override fun post(id: Int, shown: Shown, r: Reminder): Boolean { if (!enabled) return false; posted[id] = shown; return true }
        override fun cancel(id: Int) { posted.remove(id); cancelled += id }
        override fun cancelAll() { posted.clear() }
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
        scheduler = ReminderScheduler(ReminderData(db) { ItemState.Week(47, 33, 40, listOf("gym 3/4"), weekOpened) }, store, alarms, poster, today = { today }, trustedNow = { now }, elapsedNow = { elapsed }, signedOut = { signedOut }) { t ->
            taps += t
            if (t.date != today) TapResult.STALE else {
                when (t.kind) { // stands in for the widget's actions (tested in WidgetCommandsTest)
                    TapKind.TICK, TapKind.PLUS -> {
                        val n = db.getOptional("SELECT count FROM habit_logs WHERE id = ?", listOf("log-${t.id}")) { it.getLong(0)!! }
                        if (n == null) db.execute("INSERT INTO habit_logs (id, habit_id, loopdo_date, count) VALUES (?, ?, ?, ?)", listOf("log-${t.id}", t.id, t.date.toString(), t.step))
                        else db.execute("UPDATE habit_logs SET count = ? WHERE id = ?", listOf(n + t.step, "log-${t.id}"))
                    }
                    TapKind.TASK -> db.execute("UPDATE tasks SET completed_on = ? WHERE id = ?", listOf(t.date.toString(), t.id))
                    else -> Unit
                }
                TapResult.DONE
            }
        }
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
    fun caughtUpNotificationIsClearedWhenDoneElsewhere() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        now = Instant.parse("2026-11-18T12:50:00Z") // the phone was off through 18:00
        scheduler.replan()
        assertEquals(1, poster.posted.size)
        tick(gym)
        scheduler.replan()
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun alarmAfterCatchUpDoesNotRingAgain() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        now = Instant.parse("2026-11-18T12:50:00Z")
        scheduler.replan()
        poster.posted.clear()
        scheduler.fire(key(gym)) // the late alarm itself arrives after the catch-up
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun signOutClearsCaughtUpNotificationsToo() = runBlocking {
        habit("gym", "18:00")
        scheduler.replan()
        now = Instant.parse("2026-11-18T12:50:00Z")
        scheduler.replan()
        today = null
        scheduler.replan()
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun slowStartDoesNotWipe() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        today = null
        signedOut = false // the saved session is still loading after a reboot
        scheduler.replan()
        assertTrue(key(gym) in alarms.set.keys)
        assertTrue(store.scheduled().any { it.key == key(gym) })
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

    private suspend fun countHabit(title: String, target: Int, step: Int, time: String = "18:00"): String {
        val id = UUID.randomUUID().toString()
        db.execute("INSERT INTO habits (id, title, kind, target, reminder_time, step, sort_order) VALUES (?, ?, 'count', ?, ?, ?, 0)", listOf(id, title, target, time, step))
        db.execute("INSERT INTO habit_schedules (id, habit_id, valid_from, recurrence) VALUES (?, ?, '2026-11-01', 'daily')", listOf(UUID.randomUUID().toString(), id))
        return id
    }
    private suspend fun shownAt1820(key: String) { scheduler.replan(); now = Instant.parse("2026-11-18T12:50:00Z"); scheduler.fire(key) }

    @Test
    fun plusUpdatesTheCountInPlace() = runBlocking {
        val water = countHabit("water", 8, 1)
        db.execute("INSERT INTO habit_logs (id, habit_id, loopdo_date, count) VALUES (?, ?, '2026-11-18', 3)", listOf("log-$water", water))
        shownAt1820(key(water))
        scheduler.replan() // the reminder's time has passed: its button must still work
        scheduler.act("plus", key(water), 1)
        val shown = poster.posted.values.single()
        assertEquals("water · 4/8", shown.title)
        assertTrue(shown.silentUpdate)
    }

    @Test
    fun plusToTargetRemovesIt() = runBlocking {
        val water = countHabit("water", 8, 1)
        db.execute("INSERT INTO habit_logs (id, habit_id, loopdo_date, count) VALUES (?, ?, '2026-11-18', 7)", listOf("log-$water", water))
        shownAt1820(key(water))
        scheduler.act("plus", key(water), 1)
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun tickRemovesYesNo() = runBlocking {
        val gym = habit("gym", "18:00")
        shownAt1820(key(gym))
        scheduler.act("tick", key(gym), 1)
        assertEquals(TapKind.TICK, taps.single().kind)
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun doneCompletesTheTask() = runBlocking {
        val t = UUID.randomUUID().toString()
        db.execute("INSERT INTO tasks (id, title, due_date, due_time, sort_order) VALUES (?, 'pay rent', '2026-11-18', '18:00', 0)", listOf(t))
        val k = reminderKey(ReminderKind.TASK, t, d18, Ping.AT)
        shownAt1820(k)
        assertEquals("pay rent · 18:00", poster.posted.values.single().title)
        scheduler.act("done", k, 1)
        assertEquals(WidgetTap(TapKind.TASK, t, d18, done = false), taps.single())
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun snoozeSetsOneAlarmThirtyMinutesOn() = runBlocking {
        val t = UUID.randomUUID().toString()
        db.execute("INSERT INTO tasks (id, title, due_date, due_time, sort_order) VALUES (?, 'pay rent', '2026-11-18', '18:00', 0)", listOf(t))
        val k = reminderKey(ReminderKind.TASK, t, d18, Ping.AT)
        shownAt1820(k)
        scheduler.act("snooze", k, 1)
        val snooze = reminderKey(ReminderKind.TASK, t, d18, Ping.SNOOZE)
        assertEquals(elapsed + 30 * 60_000L, alarms.set[snooze])
        assertTrue(poster.posted.isEmpty())
        scheduler.replan() // a replan keeps the snooze
        assertEquals(elapsed + 30 * 60_000L, alarms.set[snooze])
        now = now.plusSeconds(1800)
        scheduler.fire(snooze)
        assertEquals("now", poster.posted.values.single().body)
    }

    @Test
    fun snoozeSilencesTheItemsEarlierPings() = runBlocking {
        val t = UUID.randomUUID().toString()
        db.execute("INSERT INTO tasks (id, title, due_date, due_time, sort_order) VALUES (?, 'pay rent', '2026-11-18', '18:00', 0)", listOf(t))
        scheduler.replan()
        now = Instant.parse("2026-11-18T12:20:00Z") // 17:50 IST: the 10-min ping
        val before = reminderKey(ReminderKind.TASK, t, d18, Ping.BEFORE)
        val at = reminderKey(ReminderKind.TASK, t, d18, Ping.AT)
        scheduler.fire(before)
        scheduler.act("snooze", before, 1) // until 18:20, so the 18:00 ping must stay quiet
        assertFalse(at in alarms.set.keys)
        scheduler.replan()
        assertFalse(at in alarms.set.keys)
        now = Instant.parse("2026-11-18T12:30:00Z")
        scheduler.fire(at) // an alarm already on its way
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun staleTapSaysTheDayHasEnded() = runBlocking {
        val gym = habit("gym", "18:00")
        shownAt1820(key(gym))
        today = d18.plusDays(1)
        scheduler.act("tick", key(gym), 1)
        val shown = poster.posted.values.single()
        assertEquals(DAY_ENDED, shown.title)
        assertEquals(5_000L, shown.timeoutMs)
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

    @Test
    fun weekPingPostsUnlessOpened() = runBlocking {
        now = Instant.parse("2026-11-22T06:30:00Z") // sun 22 nov 12:00 IST
        today = LocalDate.of(2026, 11, 22)
        scheduler.replan()
        val key = reminderKey(ReminderKind.WEEK_REVIEW, null, today!!, Ping.AT)
        assertTrue(key in alarms.set.keys)
        now = Instant.parse("2026-11-22T14:30:00Z") // 20:00
        weekOpened = true
        scheduler.fire(key)
        assertTrue(poster.posted.isEmpty())
        weekOpened = false
        scheduler.fire(key)
        assertEquals("week 47 review: 33/40 done", poster.posted.values.single().title)
    }

    @Test
    fun kindSwitchedOffClearsWhatIsShown() = runBlocking {
        val gym = habit("gym", "18:00")
        scheduler.replan()
        now = Instant.parse("2026-11-18T12:20:00Z")
        scheduler.fire(key(gym, Ping.BEFORE))
        assertEquals(1, poster.posted.size)
        db.execute("INSERT OR REPLACE INTO profiles (id, settings) VALUES (?, ?)", listOf("p1", """{"reminders":{"habits":false}}"""))
        scheduler.replan()
        assertTrue(poster.posted.isEmpty())
        assertFalse(alarms.set.keys.any { it.startsWith("habit:") })
    }
}
