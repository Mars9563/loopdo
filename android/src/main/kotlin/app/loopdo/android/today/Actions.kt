package app.loopdo.android.today

import app.loopdo.android.time.EvidenceFactory
import app.loopdo.android.time.TrustedClock
import app.loopdo.android.time.TrustedNow
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
class Actions(
    private val db: PowerSyncDatabase,
    private val clock: TrustedClock,
    private val evidence: EvidenceFactory,
    private val dateAt: suspend (Instant) -> LocalDate,
    private val userId: () -> UUID?,
) {
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
    suspend fun tickHabit(habitId: UUID) = changeCount(habitId) { kind, c -> if (kind == COUNT) c + 1 else 1 }

    /** Yes/no only: back to not done (same day). */
    suspend fun untickHabit(habitId: UUID) = changeCount(habitId) { kind, c -> if (kind == COUNT) c else 0 }

    /** Count only: the same-day −1 correction (spec 6.3), never below 0. */
    suspend fun decrementHabit(habitId: UUID) = changeCount(habitId) { kind, c -> if (kind == COUNT) maxOf(0, c - 1) else c }

    /*
     * PowerSync's PATCH carries only the columns whose value changed, and the server refuses a
     * count change without evidence. Evidence always changes between two ticks because
     * elapsedAtTickMs comes from the monotonic boot timer.
     */
    /**
     * DEBUG BUILDS ONLY (exit check 5): a +1 tick whose anchor signature is forged, so the
     * server must answer 422 INVALID_EVIDENCE and the phone must undo exactly this tick.
     */
    suspend fun debugForgedTick(habitId: UUID): Pair<Int, Int>? {
        check(app.loopdo.android.BuildConfig.DEBUG) { "debug only" }
        return changeCount(habitId, forge = true) { kind, c -> if (kind == COUNT) c + 1 else 1 }
    }

    private suspend fun changeCount(habitId: UUID, forge: Boolean = false, next: (String, Int) -> Int): Pair<Int, Int>? {
        val m = moment().let { if (forge) it.forged() else it }
        val id = instanceId(habitId, m.today).toString()
        return db.writeTransaction { tx ->
            val kind = tx.getOptional("SELECT kind FROM habits WHERE id = ?", listOf(habitId.toString())) { it.getString(0)!! }
                ?: return@writeTransaction null
            val old = tx.getOptional("SELECT count FROM habit_logs WHERE id = ?", listOf(id)) { it.getLong(0)?.toInt() ?: 0 }
            val new = next(kind, old ?: 0)
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
    suspend fun completeTask(taskId: UUID): Boolean {
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
    suspend fun reopenTask(taskId: UUID): Boolean {
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
    suspend fun addTask(title: String, due: LocalDate?, time: LocalTime?, listId: UUID?): UUID {
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

    /** Adds a line to today's journal entry (one entry per day, deterministic id). */
    suspend fun appendJournalLine(text: String) {
        val line = text.trim()
        require(line.isNotEmpty()) { "type something first." }
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

    private companion object {
        const val COUNT = "count"
        const val MAX_TITLE = 300 // tasks.title check (migration 0001)
        const val MAX_JOURNAL = 20_000 // journal_entries.text check
    }
}
