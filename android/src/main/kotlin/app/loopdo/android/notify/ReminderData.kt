package app.loopdo.android.notify

import app.loopdo.android.today.JOURNAL_DAYS_SQL
import app.loopdo.android.today.TodayData
import app.loopdo.android.today.parseRecurrence
import app.loopdo.android.today.parseWeekdays
import app.loopdo.shared.day.dateResolver
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.isDue
import app.loopdo.shared.habit.targetOn
import app.loopdo.shared.remind.HabitPlan
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderInput
import app.loopdo.shared.remind.ReminderKind
import app.loopdo.shared.remind.SeriesPlan
import app.loopdo.shared.remind.TaskPlan
import app.loopdo.shared.task.instanceId
import app.loopdo.shared.task.parseRepeatRule
import com.powersync.PowerSyncDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** What the reminders read from the phone's own database (M7): the planner's input, and an item's state when it fires. */
class ReminderData(
    private val db: PowerSyncDatabase,
    /** M8: the week ending on that Sunday, for its 20:00 review ping (null = no such ping). */
    private val week: (suspend (sunday: LocalDate) -> ItemState)? = null,
) {
    private class HabitRow(
        val id: String, val title: String, val yesNo: Boolean, val target: Int, val time: LocalTime?, val archivedOn: LocalDate?,
        val step: Int, val deleted: Boolean, val schedules: List<HabitSchedule>,
    ) {
        val habit get() = Habit(id, if (yesNo) HabitKind.YES_NO else HabitKind.COUNT, target, schedules, archivedOn)
        fun dueOn(date: LocalDate) = !deleted && (archivedOn == null || date < archivedOn) && isDue(schedules, date)
        fun doneWith(count: Int, date: LocalDate) = if (yesNo) count > 0 else count >= targetOn(habit, date)
    }

    private suspend fun habits(): List<HabitRow> {
        val schedules = db.getAll("SELECT habit_id, valid_from, recurrence, weekdays, target FROM habit_schedules") {
            it.getString(0)!! to HabitSchedule(
                LocalDate.parse(it.getString(1)!!.take(10)), parseRecurrence(it.getString(2)), parseWeekdays(it.getString(3)), it.getLong(4)?.toInt(),
            )
        }.groupBy({ it.first }, { it.second })
        return db.getAll("SELECT id, title, kind, target, reminder_time, archived_on, step, deleted_at FROM habits ORDER BY sort_order, title") {
            val id = it.getString(0)!!
            HabitRow(
                id, it.getString(1)!!, it.getString(2) != "count", (it.getLong(3)?.toInt() ?: 1).coerceAtLeast(1),
                it.getString(4)?.let(LocalTime::parse), it.getString(5)?.let { d -> LocalDate.parse(d.take(10)) },
                (it.getLong(6)?.toInt() ?: 1).coerceAtLeast(1), it.getString(7) != null, schedules[id].orEmpty(),
            )
        }
    }

    private suspend fun counts(date: LocalDate): Map<String, Int> =
        db.getAll("SELECT habit_id, count FROM habit_logs WHERE loopdo_date = ?", listOf(date.toString())) {
            it.getString(0)!! to (it.getLong(1)?.toInt() ?: 0)
        }.toMap()

    private suspend fun journalDays(): Set<LocalDate> = db.getAll(JOURNAL_DAYS_SQL) { LocalDate.parse(it.getString(0)!!.take(10)) }.toSet()

    suspend fun versions() = TodayData(db).versionsNow()

    /** The planner's input at [now]: habits, today's and tomorrow's done habits, timed tasks, timed series, journal days. */
    suspend fun input(now: Instant): ReminderInput {
        val versions = TodayData(db).versionsNow()
        val today = dateResolver(versions)(now)
        val days = listOf(today, today.plusDays(1))
        val habits = habits()
        val done = days.flatMap { d -> counts(d).filter { (id, c) -> habits.firstOrNull { it.id == id }?.doneWith(c, d) == true }.keys.map { it to d } }
        val tasks = db.getAll(
            "SELECT id, due_date, due_time, completed_on FROM tasks WHERE repeat_rule IS NULL AND due_time IS NOT NULL AND due_date IN (?, ?)",
            days.map { it.toString() },
        ) { TaskPlan(it.getString(0)!!, LocalDate.parse(it.getString(1)!!.take(10)), LocalTime.parse(it.getString(2)!!), it.getString(3) != null) }
        val series = db.getAll(
            "SELECT id, repeat_rule, due_date, generated_through, due_time FROM tasks WHERE repeat_rule IS NOT NULL AND due_time IS NOT NULL AND due_date IS NOT NULL",
        ) { c ->
            listOfNotNull(parseRepeatRule(c.getString(1)!!)?.let { rule ->
                SeriesPlan(
                    UUID.fromString(c.getString(0)!!), rule, LocalDate.parse(c.getString(2)!!.take(10)),
                    c.getString(3)?.let { LocalDate.parse(it.take(10)) }, LocalTime.parse(c.getString(4)!!),
                )
            })
        }.flatten()
        val settings = db.getOptional("SELECT settings FROM profiles LIMIT 1") { listOf(it.getString(0)) }?.firstOrNull()
        return ReminderInput(
            versions,
            habits.map { HabitPlan(it.id, it.time, it.schedules, it.archivedOn, it.deleted) },
            done.toSet(), tasks, series, journalDays(), reminderPrefsFromSettings(settings),
        )
    }

    /** [r]'s item as it is right now (the alarm re-checks before showing anything). */
    suspend fun state(r: Reminder): ItemState = when (r.kind) {
        ReminderKind.HABIT -> {
            val h = habits().firstOrNull { it.id == r.itemId }
            if (h == null || h.deleted) ItemState.Gone
            else ItemState.Habit(h.title, h.yesNo, counts(r.date)[h.id] ?: 0, targetOn(h.habit, r.date), h.step, h.dueOn(r.date))
        }
        ReminderKind.TASK -> taskState(r.itemId ?: "", r.date)
        ReminderKind.WEEK_REVIEW -> week?.invoke(r.date) ?: ItemState.Gone
        ReminderKind.JOURNAL, ReminderKind.DAY_END -> {
            val counts = counts(r.date)
            ItemState.Day(
                habits().filter { it.dueOn(r.date) && !it.doneWith(counts[it.id] ?: 0, r.date) }.map { it.title },
                r.date in journalDays(),
            )
        }
    }

    private suspend fun taskState(id: String, date: LocalDate): ItemState {
        val row = db.getOptional("SELECT title, due_date, due_time, completed_on FROM tasks WHERE id = ?", listOf(id)) {
            Triple(it.getString(0)!!, it.getString(1)?.take(10)?.let(LocalDate::parse) to it.getString(2)?.let(LocalTime::parse), it.getString(3) != null)
        }
        if (row != null) {
            val (title, due, done) = row
            val (dueDate, time) = due
            return if (dueDate != date || time == null) ItemState.Gone else ItemState.Task(title, time, done)
        }
        // A repeating task's occurrence that isn't a row yet (created at the next redraw): open, unless the series
        // already passed that date (then it was deleted).
        return db.getAll("SELECT id, title, due_time, generated_through FROM tasks WHERE repeat_rule IS NOT NULL AND due_time IS NOT NULL") {
            val through = it.getString(3)?.let { d -> LocalDate.parse(d.take(10)) }
            listOfNotNull(
                if (instanceId(UUID.fromString(it.getString(0)!!), date).toString() == id && (through == null || through < date))
                    ItemState.Task(it.getString(1)!!, LocalTime.parse(it.getString(2)!!), done = false)
                else null,
            )
        }.flatten().firstOrNull() ?: ItemState.Gone
    }
}

/** profiles.settings JSON → the owner's reminder settings (M9, spec §3.3); missing or odd = the defaults. */
fun reminderPrefsFromSettings(json: String?): app.loopdo.shared.remind.ReminderPrefs {
    val r = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json!!).jsonObject["reminders"]!!.jsonObject }.getOrNull()
        ?: return app.loopdo.shared.remind.ReminderPrefs()
    fun obj(k: String) = r[k] as? kotlinx.serialization.json.JsonObject
    fun flag(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
    fun text(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    fun num(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
    return app.loopdo.shared.remind.reminderPrefs(
        habits = flag(r["habits"]), tasks = flag(r["tasks"]),
        journal = flag(obj("journal")?.get("on")), journalAt = text(obj("journal")?.get("at")),
        dayEnd = flag(obj("dayEnd")?.get("on")), leadMinutes = num(obj("dayEnd")?.get("leadMinutes")),
        weekReview = flag(obj("weekReview")?.get("on")), weekReviewAt = text(obj("weekReview")?.get("at")),
    )
}
