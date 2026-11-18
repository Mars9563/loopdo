package app.loopdo.android.stats

import app.loopdo.android.time.EvidenceFactory
import app.loopdo.shared.stats.FocusKind
import app.loopdo.shared.stats.MAX_FOCUS
import app.loopdo.shared.stats.focusWindowOpen
import com.powersync.PowerSyncDatabase
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Setting the weekly focus (M8, spec §3.3): only on the week's Sunday-before or Monday, at most 2. The server
 * checks the same rules; the phone says no first, in plain words, and sends its time evidence for offline edits.
 */
class FocusActions(
    private val db: PowerSyncDatabase,
    private val userId: suspend () -> String?,
    /** The trusted LoopDo date. */
    private val today: suspend () -> LocalDate,
    private val evidence: EvidenceFactory?,
) {
    private suspend fun proof(day: LocalDate) = evidence?.forTick(day)?.toString()

    private suspend fun open(weekStart: LocalDate): LocalDate {
        val day = today()
        require(focusWindowOpen(weekStart, day)) { CLOSED }
        return day
    }

    suspend fun add(weekStart: LocalDate, kind: FocusKind, itemId: String, title: String, goal: Int?, deadline: LocalDate?): Result<String> = runCatching {
        val day = open(weekStart)
        val id = UUID.randomUUID().toString()
        val user = userId()
        val ev = proof(day)
        db.writeTransaction { tx ->
            val live = tx.getAll("SELECT item_id FROM weekly_focus WHERE week_start = ? AND deleted_at IS NULL", listOf(weekStart.toString())) { it.getString(0)!! }
            require(itemId !in live) { "already a focus." }
            require(live.size < MAX_FOCUS) { "pick at most $MAX_FOCUS." }
            tx.execute(
                "INSERT INTO weekly_focus (id, user_id, week_start, kind, item_id, title, goal, deadline, sort_order, evidence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(id, user, weekStart.toString(), kind.name.lowercase(), itemId, title.take(200), goal, deadline?.toString(), live.size, ev),
            )
        }
        id
    }

    suspend fun setGoal(id: String, goal: Int): Result<Unit> = runCatching {
        val day = open(weekOf(id))
        db.execute("UPDATE weekly_focus SET goal = ?, evidence = ? WHERE id = ?", listOf(goal.coerceIn(1, 7), proof(day), id))
    }

    /** Removed = deleted_at set (inside the window only), so a past promise can never vanish. */
    suspend fun remove(id: String): Result<Unit> = runCatching {
        val day = open(weekOf(id))
        db.execute("UPDATE weekly_focus SET deleted_at = ?, evidence = ? WHERE id = ?", listOf(Instant.now().toString(), proof(day), id))
    }

    private suspend fun weekOf(id: String): LocalDate =
        db.get("SELECT week_start FROM weekly_focus WHERE id = ?", listOf(id)) { LocalDate.parse(it.getString(0)!!.take(10)) }

    companion object {
        const val CLOSED = "the focus can be set on sunday or monday."
    }
}
