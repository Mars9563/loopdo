package app.loopdo.android.today

import app.loopdo.android.time.EvidenceFactory
import app.loopdo.android.time.TrustedClock
import app.loopdo.android.time.TrustedNow
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.habit.isDue
import app.loopdo.shared.habit.scheduleOn
import app.loopdo.shared.task.instanceId
import app.loopdo.shared.task.uuidV5
import com.powersync.PowerSyncDatabase
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Everything the today screen can change. Each action is ONE local transaction, so the
 * uploader sends it as one batch and a 422 undoes exactly it (API contract). "Today" is
 * judged on the trusted clock, never the phone's adjustable wall clock.
 */
/** A tap from a screen still showing a day that has ended ([today] is the real LoopDo date). */
class StaleDayException(val today: LocalDate) : Exception("the day has ended.")

/**
 * What the today screen and the widget ask of the database (a fake in JVM tests).
 * Habit changes take the date the surface is showing: if that day has ended they throw
 * [StaleDayException] and write nothing, so a stale screen can never tick the wrong day.
 */
interface TodayActions {
    /** Yes/no: done. Count: + [by] (the habit's step from the [+step] button). */
    suspend fun tickHabit(habitId: UUID, shownDate: LocalDate? = null, by: Int = 1): Pair<Int, Int>?
    /** Count: set the exact number (long-press → type), never below 0. Yes/no: >0 = done. */
    suspend fun setHabitCount(habitId: UUID, count: Int, shownDate: LocalDate? = null): Pair<Int, Int>?
    /** "All done": a count habit jumps to today's target (the [ ] on a count habit). */
    suspend fun completeHabit(habitId: UUID, shownDate: LocalDate? = null): Pair<Int, Int>?
    suspend fun untickHabit(habitId: UUID, shownDate: LocalDate? = null): Pair<Int, Int>?
    suspend fun decrementHabit(habitId: UUID, shownDate: LocalDate? = null): Pair<Int, Int>?
    suspend fun debugForgedTick(habitId: UUID, shownDate: LocalDate? = null): Pair<Int, Int>?
    suspend fun completeTask(taskId: UUID): Boolean
    suspend fun reopenTask(taskId: UUID): Boolean
    suspend fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: UUID?): UUID
    /** The add sheet's [↻ repeat] (M5): a series from [due] (today if none), with its occurrences up to today. */
    suspend fun addRepeatingTask(title: String, due: LocalDate?, time: LocalTime?, listId: UUID?, repeat: app.loopdo.shared.task.RepeatRule): UUID =
        throw IllegalStateException("repeating tasks can't be added here.")
    suspend fun appendJournalLine(text: String)
    /**
     * Stores [text] and [mood] as [date]'s entry (past entries stay editable; future days can't be
     * written). [base] = the entry text the edit started from: if the entry changed since, the edit is
     * merged on top (mergeJournal), never overwriting newer text.
     */
    suspend fun saveJournal(date: LocalDate, text: String, mood: Int?, base: String? = null)
}

class Actions(
    private val db: PowerSyncDatabase,
    private val clock: TrustedClock,
    private val evidence: EvidenceFactory,
    private val dateAt: suspend (Instant) -> LocalDate,
    private val userId: () -> UUID?,
    /** Makes a repeating series (TaskActions.saveTask); set by the app graph. */
    private val series: (suspend (app.loopdo.android.tasks.TaskDraft) -> UUID)? = null,
    /** The M6 journal (stamped lines); set by the app graph. */
    private val journal: app.loopdo.android.journal.JournalActions? = null,
) : TodayActions {
    private class Moment(val now: TrustedNow, val today: LocalDate, val evidence: String) {
        val verified get() = if (now.verified) 1 else 0
    }

    private fun Moment.forged(): Moment {
        val ev = kotlinx.serialization.json.Json.parseToJsonElement(evidence).jsonObject.toMutableMap()
        ev["anchor"] = kotlinx.serialization.json.buildJsonObject {
            put("serverTime", now.instant.toString())
            put("elapsedRealtimeMs", 0L)
            put("bootCount", 0)
            put("sig", "forged")
        }
        return Moment(now, today, kotlinx.serialization.json.JsonObject(ev).toString())
    }

    private suspend fun moment(): Moment {
        val now = clock.now()
        val today = dateAt(now.instant)
        return Moment(now, today, evidence.forTick(today).toString())
    }

    /** Yes/no: done. Count: +1 (no cap). Returns (before, after), or null if nothing changed. */
    override suspend fun tickHabit(habitId: UUID, shownDate: LocalDate?, by: Int) =
        changeCount(habitId, shownDate) { kind, c, _ -> if (kind == COUNT) c + maxOf(1, by) else 1 }

    override suspend fun setHabitCount(habitId: UUID, count: Int, shownDate: LocalDate?) =
        changeCount(habitId, shownDate) { kind, _, _ -> if (kind == COUNT) maxOf(0, count) else if (count > 0) 1 else 0 }

    override suspend fun completeHabit(habitId: UUID, shownDate: LocalDate?) =
        changeCount(habitId, shownDate) { kind, c, target -> if (kind == COUNT) maxOf(c, target) else 1 }

    /** Yes/no only: back to not done (same day). */
    override suspend fun untickHabit(habitId: UUID, shownDate: LocalDate?) = changeCount(habitId, shownDate) { kind, c, _ -> if (kind == COUNT) c else 0 }

    /** Count only: the same-day −1 correction (spec 6.3), never below 0. */
    override suspend fun decrementHabit(habitId: UUID, shownDate: LocalDate?) = changeCount(habitId, shownDate) { kind, c, _ -> if (kind == COUNT) maxOf(0, c - 1) else c }

    /*
     * PowerSync's PATCH carries only the columns whose value changed, and the server refuses a
     * count change without evidence. Evidence always changes between two ticks because
     * elapsedAtTickMs comes from the monotonic boot timer.
     */
    /**
     * DEBUG BUILDS ONLY (exit check 5): a +1 tick whose anchor signature is forged, so the
     * server must answer 422 INVALID_EVIDENCE and the phone must undo exactly this tick.
     */
    override suspend fun debugForgedTick(habitId: UUID, shownDate: LocalDate?): Pair<Int, Int>? {
        check(app.loopdo.android.BuildConfig.DEBUG) { "debug only" }
        return changeCount(habitId, shownDate, forge = true) { kind, c, _ -> if (kind == COUNT) c + 1 else 1 }
    }

    private suspend fun changeCount(habitId: UUID, shownDate: LocalDate?, forge: Boolean = false, next: (kind: String, count: Int, target: Int) -> Int): Pair<Int, Int>? {
        val m = moment().let { if (forge) it.forged() else it }
        if (shownDate != null && shownDate != m.today) throw StaleDayException(m.today)
        val id = instanceId(habitId, m.today).toString()
        return db.writeTransaction { tx ->
            val (kind, archivedOn, habitTarget) = tx.getOptional("SELECT kind, archived_on, target FROM habits WHERE id = ?", listOf(habitId.toString())) {
                Triple(it.getString(0)!!, it.getString(1)?.let { d -> LocalDate.parse(d.take(10)) }, (it.getLong(2) ?: 1L).toInt())
            } ?: return@writeTransaction null
            // Only today's copy of a habit that is due today (shared rule), never an archived one.
            val schedules = tx.getAll("SELECT valid_from, recurrence, weekdays, target FROM habit_schedules WHERE habit_id = ?", listOf(habitId.toString())) {
                HabitSchedule(
                    LocalDate.parse(it.getString(0)!!.take(10)),
                    parseRecurrence(it.getString(1)),
                    parseWeekdays(it.getString(2)),
                    it.getLong(3)?.toInt(),
                )
            }
            if ((archivedOn != null && m.today >= archivedOn) || !isDue(schedules, m.today)) return@writeTransaction null
            val old = tx.getOptional("SELECT count FROM habit_logs WHERE id = ?", listOf(id)) { it.getLong(0)?.toInt() ?: 0 }
            val target = scheduleOn(schedules, m.today)?.target ?: habitTarget // the target of today (shared rule)
            val new = next(kind, old ?: 0, target)
            if (new == (old ?: 0)) return@writeTransaction null
            val completedAt = if (new > 0) m.now.instant.toString() else null
            if (old == null) {
                tx.execute(
                    "INSERT INTO habit_logs (id, user_id, habit_id, loopdo_date, count, completed_at, evidence, verified) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(id, userId()?.toString(), habitId.toString(), m.today.toString(), new, completedAt, m.evidence, m.verified),
                )
            } else {
                tx.execute(
                    "UPDATE habit_logs SET count = ?, completed_at = ?, evidence = ?, verified = ? WHERE id = ?",
                    listOf(new, completedAt, m.evidence, m.verified, id),
                )
            }
            (old ?: 0) to new
        }
    }

    /** Marks the task done today (a late task records its real completion day). False if already done. */
    override suspend fun completeTask(taskId: UUID): Boolean {
        val m = moment()
        return db.writeTransaction { tx ->
            tx.getOptional("SELECT 1 FROM tasks WHERE id = ? AND completed_at IS NULL", listOf(taskId.toString())) { 1 }
                ?: return@writeTransaction false
            tx.execute(
                "UPDATE tasks SET completed_at = ?, completed_on = ?, evidence = ?, verified = ? WHERE id = ?",
                listOf(m.now.instant.toString(), m.today.toString(), m.evidence, m.verified, taskId.toString()),
            )
            true
        }
    }

    /**
     * Undoes a completion. A dated task only on the day it was done (with evidence); an
     * undated inbox task any day, without evidence (owner, 2026-11-15). False if refused.
     */
    override suspend fun reopenTask(taskId: UUID): Boolean {
        val m = moment()
        return db.writeTransaction { tx ->
            val (due, on) = tx.getOptional("SELECT due_date, completed_on FROM tasks WHERE id = ? AND completed_at IS NOT NULL", listOf(taskId.toString())) {
                it.getString(0) to it.getString(1)
            } ?: return@writeTransaction false
            if (due != null && on != m.today.toString()) return@writeTransaction false
            tx.execute(
                "UPDATE tasks SET completed_at = NULL, completed_on = NULL, verified = NULL, evidence = ? WHERE id = ?",
                listOf(if (due != null) m.evidence else null, taskId.toString()),
            )
            true
        }
    }

    /** A new task at the end of the order. No date + no list = inbox. Returns its id. */
    override suspend fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: UUID?): UUID {
        val t = title.trim()
        require(t.isNotEmpty()) { "type something first." }
        require(t.length <= MAX_TITLE) { "that's too long. keep it under $MAX_TITLE characters." }
        require(time == null || due != null) { "pick a date before a time." }
        val id = UUID.randomUUID()
        db.writeTransaction { tx ->
            val order = tx.get("SELECT coalesce(max(sort_order), 0) + 1 FROM tasks") { it.getLong(0)!! }
            tx.execute(
                "INSERT INTO tasks (id, user_id, list_id, title, due_date, due_time, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)",
                listOf(id.toString(), userId()?.toString(), listId?.toString(), t, due?.toString(), time?.toString(), order),
            )
        }
        return id
    }

    override suspend fun addRepeatingTask(title: String, due: LocalDate?, time: LocalTime?, listId: UUID?, repeat: app.loopdo.shared.task.RepeatRule): UUID {
        val make = series ?: return super.addRepeatingTask(title, due, time, listId, repeat)
        return make(app.loopdo.android.tasks.TaskDraft(title, null, due, time, listId, repeat))
    }

    /** Adds a line to today's journal entry (one entry per day, deterministic id). */
    override suspend fun appendJournalLine(text: String) {
        val line = text.trim()
        require(line.isNotEmpty()) { "type something first." }
        // M6: a journal line carries the time it was saved.
        journal?.let { return it.addLines(dateAt(clock.now().instant), line) }
        val user = userId() ?: throw IllegalStateException("not signed in")
        val today = dateAt(clock.now().instant)
        val id = uuidV5(user, "journal:$today").toString()
        db.writeTransaction { tx ->
            val old = tx.getOptional("SELECT text FROM journal_entries WHERE id = ?", listOf(id)) { it.getString(0) ?: "" }
            val new = if (old.isNullOrBlank()) line else old.trimEnd() + "\n" + line
            require(new.length <= MAX_JOURNAL) { "today's entry is full ($MAX_JOURNAL characters)." }
            if (old == null) {
                tx.execute(
                    "INSERT INTO journal_entries (id, user_id, loopdo_date, text) VALUES (?, ?, ?, ?)",
                    listOf(id, user.toString(), today.toString(), new),
                )
            } else {
                tx.execute("UPDATE journal_entries SET text = ? WHERE id = ?", listOf(new, id))
            }
        }
    }

    override suspend fun saveJournal(date: LocalDate, text: String, mood: Int?, base: String?) {
        val user = userId() ?: throw IllegalStateException("not signed in")
        val today = dateAt(clock.now().instant)
        require(date <= today) { "that day hasn't started yet." }
        require(mood == null || mood in app.loopdo.shared.Limits.MOOD_RANGE) { "pick one of the five moods." }
        val id = uuidV5(user, "journal:$date").toString()
        db.writeTransaction { tx ->
            val current = tx.getOptional("SELECT text FROM journal_entries WHERE id = ?", listOf(id)) { it.getString(0).orEmpty() }
            val merged = if (base != null && current != null && current != base) app.loopdo.android.popup.mergeJournal(current, base, text) else text
            require(merged.length <= MAX_JOURNAL) { "today's entry is full ($MAX_JOURNAL characters)." }
            if (current != null) {
                tx.execute("UPDATE journal_entries SET text = ?, mood = ? WHERE id = ?", listOf(merged, mood, id))
            } else {
                tx.execute(
                    "INSERT INTO journal_entries (id, user_id, loopdo_date, text, mood) VALUES (?, ?, ?, ?, ?)",
                    listOf(id, user.toString(), date.toString(), merged, mood),
                )
            }
        }
    }

    private companion object {
        const val COUNT = "count"
        const val MAX_TITLE = 300 // tasks.title check (migration 0001)
        const val MAX_JOURNAL = 20_000 // journal_entries.text check
    }
}
