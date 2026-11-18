package app.loopdo.android.notify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.loopdo.android.sync.LoopDoSchema
import app.loopdo.shared.remind.Ping
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import app.loopdo.shared.remind.reminderKey
import app.loopdo.shared.task.RepeatRule
import app.loopdo.shared.task.instanceId
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Runs on the phone: what the reminders read from a real PowerSync database. */
@RunWith(AndroidJUnit4::class)
class ReminderDataTest {
    private lateinit var db: PowerSyncDatabase
    private lateinit var data: ReminderData
    private val now = Instant.parse("2026-11-18T06:30:00Z") // 12:00 IST, LoopDo wed 18 nov
    private val d18 = LocalDate.of(2026, 11, 18)

    @Before
    fun open() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = PowerSyncDatabase(DatabaseDriverFactory(ctx), LoopDoSchema.schema, dbFilename = "test-${UUID.randomUUID()}.db")
        db.execute(
            "INSERT INTO day_settings_versions (id, effective_at, time_zone, day_end) VALUES (?, '1970-01-01T00:00:00Z', 'Asia/Kolkata', '04:00')",
            listOf(UUID.randomUUID().toString()),
        )
        data = ReminderData(db)
    }

    @After
    fun close() = runBlocking { db.close(); app.loopdo.android.deleteTestDatabases() }

    private suspend fun habit(title: String, kind: String = "yes_no", target: Int = 1, time: String? = null, step: Int = 1, order: Int = 0): String {
        val id = UUID.randomUUID().toString()
        db.execute(
            "INSERT INTO habits (id, title, kind, target, reminder_time, step, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
            listOf(id, title, kind, target, time, step, order),
        )
        db.execute(
            "INSERT INTO habit_schedules (id, habit_id, valid_from, recurrence) VALUES (?, ?, '2026-11-01', 'daily')",
            listOf(UUID.randomUUID().toString(), id),
        )
        return id
    }

    private suspend fun log(habit: String, count: Int, date: LocalDate = d18) = db.execute(
        "INSERT INTO habit_logs (id, habit_id, loopdo_date, count) VALUES (?, ?, ?, ?)",
        listOf(UUID.randomUUID().toString(), habit, date.toString(), count),
    )

    private suspend fun task(title: String, due: LocalDate?, time: String?, done: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        db.execute(
            "INSERT INTO tasks (id, title, due_date, due_time, completed_on, sort_order) VALUES (?, ?, ?, ?, ?, 0)",
            listOf(id, title, due?.toString(), time, if (done) d18.toString() else null),
        )
        return id
    }

    private fun r(kind: ReminderKind, id: String?) = Reminder(reminderKey(kind, id, d18, Ping.AT), kind, id, d18, now, Ping.AT)

    @Test
    fun inputHasTimedHabitsTasksAndSeries() = runBlocking {
        val gym = habit("gym", time = "18:00")
        val rent = task("pay rent", d18, "18:30")
        task("no time", d18, null)
        val template = UUID.randomUUID()
        db.execute(
            "INSERT INTO tasks (id, title, due_date, due_time, repeat_rule, generated_through, sort_order) VALUES (?, 'stretch', '2026-11-01', '08:30', ?, '2026-11-18', 0)",
            listOf(template.toString(), """{"type":"daily"}"""),
        )
        val input = data.input(now)
        assertEquals(1, input.versions.size)
        assertEquals(listOf(gym to LocalTime.of(18, 0)), input.habits.map { it.id to it.reminderTime })
        assertEquals(listOf(rent), input.tasks.map { it.id })
        assertEquals(listOf(Triple(template, RepeatRule.Daily as RepeatRule, LocalTime.of(8, 30))), input.series.map { Triple(it.templateId, it.rule, it.dueTime) })
        assertEquals(LocalDate.of(2026, 11, 18), input.series.single().generatedThrough)
    }

    @Test
    fun doneCountHabitIsInDoneHabits() = runBlocking {
        val water = habit("water", "count", target = 8)
        val kanji = habit("kanji", "count", target = 50)
        log(water, 8)
        log(kanji, 49)
        val done = data.input(now).doneHabits
        assertTrue((water to d18) in done)
        assertFalse((kanji to d18) in done)
    }

    @Test
    fun stateOfACountHabit() = runBlocking {
        val water = habit("water", "count", target = 8, step = 1)
        log(water, 3)
        assertEquals(ItemState.Habit("water", yesNo = false, count = 3, target = 8, step = 1, dueToday = true), data.state(r(ReminderKind.HABIT, water)))
    }

    @Test
    fun stateOfDayEnd() = runBlocking {
        habit("kanji", order = 2)
        habit("gym", order = 1)
        val done = habit("meditate", order = 0)
        log(done, 1)
        val entry = UUID.randomUUID().toString()
        db.execute("INSERT INTO journal_entries (id, loopdo_date, text) VALUES (?, '2026-11-18', '')", listOf(entry))
        db.execute(
            "INSERT INTO journal_lines (id, entry_id, text, edited_at, sort_order) VALUES (?, ?, 'a line', '2026-11-18T06:00:00Z', 0)",
            listOf(UUID.randomUUID().toString(), entry),
        )
        assertEquals(ItemState.Day(listOf("gym", "kanji"), journalWritten = true), data.state(r(ReminderKind.DAY_END, null)))
        assertEquals(ItemState.Day(listOf("gym", "kanji"), journalWritten = true), data.state(r(ReminderKind.JOURNAL, null)))
    }

    @Test
    fun deletedTaskIsGone() = runBlocking {
        val t = task("gone soon", d18, "18:30")
        assertEquals(ItemState.Task("gone soon", LocalTime.of(18, 30), done = false), data.state(r(ReminderKind.TASK, t)))
        db.execute("DELETE FROM tasks WHERE id = ?", listOf(t))
        assertEquals(ItemState.Gone, data.state(r(ReminderKind.TASK, t)))
    }

    @Test
    fun seriesOccurrenceNotYetCreatedIsOpen() = runBlocking {
        val template = UUID.randomUUID()
        db.execute(
            "INSERT INTO tasks (id, title, due_date, due_time, repeat_rule, generated_through, sort_order) VALUES (?, 'stretch', '2026-11-01', '08:30', ?, '2026-11-17', 0)",
            listOf(template.toString(), """{"type":"daily"}"""),
        )
        assertEquals(ItemState.Task("stretch", LocalTime.of(8, 30), done = false), data.state(r(ReminderKind.TASK, instanceId(template, d18).toString())))
    }
}
