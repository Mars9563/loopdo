package app.loopdo.client.tasks

import app.loopdo.client.sync.oneTransaction
import app.loopdo.client.time.TrustedClock
import app.loopdo.shared.task.RepeatRule
import app.loopdo.shared.task.toJson
import com.powersync.PowerSyncDatabase
import com.powersync.db.internal.PowerSyncTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** What the task editor saves. [repeat] null = a one-off task; [due] null = the inbox. */
data class TaskDraft(
    val title: String,
    val notes: String?,
    val due: LocalDate?,
    val time: LocalTime?,
    val listId: UUID?,
    val repeat: RepeatRule?,
)

/** What the task screens ask of the database (a fake in JVM tests). */
interface TaskCommands {
    suspend fun saveTask(id: UUID?, draft: TaskDraft): UUID
    suspend fun moveTask(id: UUID, due: LocalDate?, time: LocalTime?)
    suspend fun moveToList(id: UUID, listId: UUID?)
    suspend fun deleteTask(id: UUID)
    suspend fun stopRepeating(templateId: UUID)
    suspend fun editSeries(templateId: UUID, draft: TaskDraft): UUID
    suspend fun addSubtask(taskId: UUID, title: String): UUID
    suspend fun toggleSubtask(id: UUID)
    suspend fun deleteSubtask(id: UUID)
    suspend fun saveList(id: UUID?, name: String): UUID
    suspend fun deleteList(id: UUID)
    suspend fun ensureInstances(today: LocalDate)
}

/**
 * Tasks, checklists, lists and repeating tasks (M5). Each action is ONE local transaction.
 * A repeating task is a template row (repeat_rule set, due_date = its first day) plus one
 * ordinary task per occurrence, created up to today with a deterministic id, so two devices
 * never make the same occurrence twice and a deleted one never comes back.
 */
class TaskActions(
    private val db: PowerSyncDatabase,
    private val userId: () -> UUID?,
    private val dateAt: suspend (Instant) -> LocalDate,
    private val clock: TrustedClock,
    /** Proof of when a move was made (M6.5, M5 item 4): an on-time move made offline isn't marked late. */
    private val evidence: app.loopdo.client.time.EvidenceFactory? = null,
) : TaskCommands {
    /** The re-dated task's evidence column: proof of the day the move was made (the server checks it, never stores it). */
    private fun moveProof(today: LocalDate): String? = evidence?.forTick(today)?.toString()

    private suspend fun today(): LocalDate = dateAt(clock.now().instant)
    private fun user(): String? = userId()?.toString()

    /**
     * New ([id] null): a task, or with [TaskDraft.repeat] a series starting on its date (today if
     * none) with its occurrences up to today. Edit: this one task changes; giving a plain task a
     * repeat starts a series from the day after it (the task itself stays as it is).
     * Returns the task's id (the template's for a new series).
     */
    override suspend fun saveTask(id: UUID?, draft: TaskDraft): UUID {
        val d = checkTask(draft)
        val today = today()
        return db.oneTransaction { tx ->
            if (id == null) {
                if (d.repeat == null) tx.insertTask(d, null).also { return@oneTransaction it }
                val t = tx.insertTask(d.copy(due = d.due ?: today), d.repeat)
                tx.template(t)?.let { tx.createInstances(it, today, user()) }
                return@oneTransaction t
            }
            val row = tx.getOptional(
                "SELECT repeat_rule IS NOT NULL, template_id IS NOT NULL, completed_at IS NOT NULL, due_date, due_time FROM tasks WHERE id = ?", listOf(id.toString()),
            ) { Existing(it.getLong(0) == 1L, it.getLong(1) == 1L, it.getLong(2) == 1L, it.getString(3)?.let { d -> LocalDate.parse(d.take(10)) }, it.getString(4)?.let(LocalTime::parse)) }
                ?: throw IllegalStateException("that task is gone.")
            val isInstance = row.isInstance
            check(!row.isTemplate) { "edit the whole series with \"this and future\"." }
            // A done task keeps its date: re-dating a late one would make it on time (the server refuses it too).
            check(!row.done || (d.due == row.due && d.time == row.time)) { "it's done, so its date stays. reopen it first." }
            // A re-date carries its proof in the same change (review of M6.5: a second PATCH came too late to count).
            val proof = if (!row.done && d.due != row.due) moveProof(today) else null
            tx.execute(
                "UPDATE tasks SET title = ?, notes = ?, due_date = ?, due_time = ?, list_id = ?, evidence = coalesce(?, evidence) WHERE id = ?",
                listOf(d.title, d.notes, d.due?.toString(), d.time?.toString(), d.listId?.toString(), proof, id.toString()),
            )
            if (d.repeat != null && !isInstance) {
                val due = requireNotNull(d.due) { "pick a date for the first one." }
                val t = tx.insertTask(d.copy(due = due.plusDays(1)), d.repeat)
                tx.template(t)?.let { tx.createInstances(it, today, user()) }
            }
            id
        }
    }

    /** Another day (or no date = inbox). A late task keeps its late history: the server records it. */
    override suspend fun moveTask(id: UUID, due: LocalDate?, time: LocalTime?) {
        val today = today()
        require(due == null || due >= today) { "pick today or a later day." }
        require(time == null || due != null) { "pick a date before a time." }
        db.oneTransaction { tx ->
            tx.openTask(id)
            tx.execute(
                "UPDATE tasks SET due_date = ?, due_time = ?, evidence = coalesce(?, evidence) WHERE id = ?",
                listOf(due?.toString(), time?.toString(), moveProof(today), id.toString()),
            )
        }
    }

    /** Another list (null = the inbox). */
    override suspend fun moveToList(id: UUID, listId: UUID?) {
        db.oneTransaction { tx ->
            tx.execute("UPDATE tasks SET list_id = ? WHERE id = ?", listOf(listId?.toString(), id.toString()))
        }
    }

    /** The task and its checklist. A deleted occurrence of a series is never created again. */
    override suspend fun deleteTask(id: UUID) {
        db.oneTransaction { tx ->
            // A done task is part of a past day (active days, a late record); the server refuses it too.
            val done = tx.getOptional("SELECT completed_at IS NOT NULL FROM tasks WHERE id = ?", listOf(id.toString())) { it.getLong(0) == 1L } ?: false
            check(!done) { "a done task stays in your history, so it can't be deleted." }
            tx.execute("DELETE FROM subtasks WHERE task_id = ?", listOf(id.toString()))
            tx.execute("DELETE FROM tasks WHERE id = ?", listOf(id.toString()))
        }
    }

    /** [stop repeating]: no new occurrences; the ones that exist stay. */
    override suspend fun stopRepeating(templateId: UUID) {
        db.oneTransaction { tx ->
            tx.execute("UPDATE tasks SET generated_through = ? WHERE id = ? AND repeat_rule IS NOT NULL", listOf(STOPPED.toString(), templateId.toString()))
        }
    }

    /**
     * "This and future": the series restarts today with [draft]. Open occurrences from today on are
     * replaced; done and past ones stay as they were. Returns the new series' id (the old one is
     * stopped, so its history keeps its own id). A draft without a repeat just ends the series.
     */
    override suspend fun editSeries(templateId: UUID, draft: TaskDraft): UUID {
        val d = checkTask(draft)
        val today = today()
        return db.oneTransaction { tx ->
            val old = tx.template(templateId) ?: throw IllegalStateException("that repeating task is gone.")
            // Ending the series (no repeat) keeps today's open copy, with the edit (owner, 2026-11-18); only later ones go.
            val ending = d.repeat == null
            val replaced = tx.getAll(
                "SELECT id FROM tasks WHERE template_id = ? AND completed_at IS NULL AND occurrence_date ${if (ending) ">" else ">="} ?",
                listOf(templateId.toString(), today.toString()),
            ) { it.getString(0)!! }
            replaced.forEach { tx.execute("DELETE FROM subtasks WHERE task_id = ?", listOf(it)); tx.execute("DELETE FROM tasks WHERE id = ?", listOf(it)) }
            tx.execute("UPDATE tasks SET generated_through = ? WHERE id = ?", listOf(STOPPED.toString(), templateId.toString()))
            if (ending) {
                tx.execute(
                    "UPDATE tasks SET title = ?, notes = ?, due_time = ?, list_id = ? WHERE template_id = ? AND occurrence_date = ? AND completed_at IS NULL",
                    listOf(d.title, d.notes, d.time?.toString(), d.listId?.toString(), templateId.toString(), today.toString()),
                )
                return@oneTransaction templateId
            }
            val doneToday = tx.getOptional(
                "SELECT 1 FROM tasks WHERE template_id = ? AND occurrence_date = ? AND completed_at IS NOT NULL", listOf(templateId.toString(), today.toString()),
            ) { true } != null
            val start = maxOf(today, d.due ?: today)
            val t = tx.insertTask(d.copy(due = start), d.repeat, generatedThrough = today.takeIf { doneToday && start == today })
            tx.getAll("SELECT title, sort_order FROM subtasks WHERE task_id = ?", listOf(old.id.toString())) { it.getString(0)!! to (it.getLong(1) ?: 0L) }
                .forEach { (title, sort) -> tx.insertSubtask(t, title, sort) }
            tx.template(t)?.let { tx.createInstances(it, today, user()) }
            t
        }
    }

    override suspend fun addSubtask(taskId: UUID, title: String): UUID {
        val text = title.trim()
        require(text.isNotEmpty()) { "type something first." }
        require(text.length <= MAX_TITLE) { "that's too long. keep it under $MAX_TITLE characters." }
        return db.oneTransaction { tx ->
            val order = tx.get("SELECT coalesce(max(sort_order), -1) + 1 FROM subtasks WHERE task_id = ?", listOf(taskId.toString())) { it.getLong(0)!! }
            tx.insertSubtask(taskId, text, order)
        }
    }

    override suspend fun toggleSubtask(id: UUID) {
        db.oneTransaction { tx -> tx.execute("UPDATE subtasks SET done = 1 - coalesce(done, 0) WHERE id = ?", listOf(id.toString())) }
    }

    override suspend fun deleteSubtask(id: UUID) {
        db.oneTransaction { tx -> tx.execute("DELETE FROM subtasks WHERE id = ?", listOf(id.toString())) }
    }

    /** A new list at the end ([id] null) or a rename. */
    override suspend fun saveList(id: UUID?, name: String): UUID {
        val n = name.trim()
        require(n.isNotEmpty()) { "type a name first." }
        require(n.length <= MAX_LIST) { "keep the name under $MAX_LIST characters." }
        return db.oneTransaction { tx ->
            if (id != null) {
                tx.execute("UPDATE lists SET name = ? WHERE id = ?", listOf(n, id.toString()))
                id
            } else {
                val new = UUID.randomUUID()
                val order = tx.get("SELECT coalesce(max(sort_order), -1) + 1 FROM lists") { it.getLong(0)!! }
                tx.execute("INSERT INTO lists (id, user_id, name, sort_order) VALUES (?, ?, ?, ?)", listOf(new.toString(), user(), n, order))
                new
            }
        }
    }

    /** Deletes the list; its tasks (open, done and repeating) move to the inbox first. Nothing else is deleted. */
    override suspend fun deleteList(id: UUID) {
        db.oneTransaction { tx ->
            tx.execute("UPDATE tasks SET list_id = NULL WHERE list_id = ?", listOf(id.toString()))
            tx.execute("DELETE FROM lists WHERE id = ?", listOf(id.toString()))
        }
    }

    /** Creates every series' missing occurrences up to [today] (app start, day rollover, widget redraw). */
    override suspend fun ensureInstances(today: LocalDate) {
        db.oneTransaction { tx -> tx.openTemplates(today).forEach { tx.createInstances(it, today, user()) } }
    }

    // --- inside a transaction ------------------------------------------------

    private fun PowerSyncTransaction.insertTask(d: TaskDraft, repeat: RepeatRule?, generatedThrough: LocalDate? = null): UUID {
        val id = UUID.randomUUID()
        val order = get("SELECT coalesce(max(sort_order), 0) + 1 FROM tasks") { it.getLong(0)!! }
        execute(
            "INSERT INTO tasks (id, user_id, list_id, title, notes, due_date, due_time, repeat_rule, generated_through, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                id.toString(), user(), d.listId?.toString(), d.title, d.notes, d.due?.toString(), d.time?.toString(),
                repeat?.toJson(), generatedThrough?.toString(), order,
            ),
        )
        return id
    }

    private fun PowerSyncTransaction.insertSubtask(taskId: UUID, title: String, order: Long): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO subtasks (id, user_id, task_id, title, done, sort_order) VALUES (?, ?, ?, ?, 0, ?)",
            listOf(id.toString(), user(), taskId.toString(), title, order),
        )
        return id
    }

    private fun PowerSyncTransaction.openTask(id: UUID) {
        val done = getOptional("SELECT completed_at IS NOT NULL FROM tasks WHERE id = ?", listOf(id.toString())) { it.getLong(0) == 1L }
            ?: throw IllegalStateException("that task is gone.")
        check(!done) { "it's done already." }
    }

    private fun checkTask(d: TaskDraft): TaskDraft {
        val title = d.title.trim()
        require(title.isNotEmpty()) { "type something first." }
        require(title.length <= MAX_TITLE) { "that's too long. keep it under $MAX_TITLE characters." }
        val notes = d.notes?.trim()?.ifEmpty { null }
        require(notes == null || notes.length <= MAX_NOTES) { "the notes are too long. keep them under $MAX_NOTES characters." }
        require(d.time == null || d.due != null || d.repeat != null) { "pick a date before a time." }
        return d.copy(title = title, notes = notes)
    }

    private class Existing(val isTemplate: Boolean, val isInstance: Boolean, val done: Boolean, val due: LocalDate?, val time: LocalTime?)

    private companion object {
        const val MAX_TITLE = 300 // tasks.title and subtasks.title checks (migration 0001)
        const val MAX_NOTES = 10_000 // tasks.notes check
        const val MAX_LIST = 80 // lists.name check
    }
}
