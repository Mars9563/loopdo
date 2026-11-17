package app.loopdo.android.tasks

import app.loopdo.shared.task.RepeatRule
import app.loopdo.shared.task.instanceId
import app.loopdo.shared.task.instancesToCreate
import app.loopdo.shared.task.parseRepeatRule
import app.loopdo.shared.task.uuidV5
import com.powersync.db.internal.PowerSyncTransaction
import java.time.LocalDate
import java.util.UUID

/** generated_through of a series that was stopped: nothing is ever created after it (the server keeps the max). */
internal val STOPPED: LocalDate = LocalDate.of(9999, 12, 31)

/** A repeating task's template row: what each occurrence copies. [start] = its due_date. */
internal class Template(
    val id: UUID, val rule: RepeatRule, val start: LocalDate, val generatedThrough: LocalDate?,
    val title: String, val notes: String?, val listId: String?, val time: String?,
)

private const val TEMPLATE_COLUMNS = "id, repeat_rule, due_date, generated_through, title, notes, list_id, due_time"

private fun PowerSyncTransaction.templates(where: String, args: List<Any?>): List<Template> =
    getAll("SELECT $TEMPLATE_COLUMNS FROM tasks WHERE repeat_rule IS NOT NULL AND $where", args) { c ->
        val rule = c.getString(1)?.let(::parseRepeatRule)
        val start = c.getString(2)?.let { LocalDate.parse(it.take(10)) }
        listOfNotNull(if (rule == null || start == null) null else Template(
            UUID.fromString(c.getString(0)!!), rule, start, c.getString(3)?.let { LocalDate.parse(it.take(10)) },
            c.getString(4)!!, c.getString(5), c.getString(6), c.getString(7),
        ))
    }.flatten()

internal fun PowerSyncTransaction.template(id: UUID): Template? = templates("id = ?", listOf(id.toString())).firstOrNull()

/** Every series that may owe an occurrence by [today]. */
internal fun PowerSyncTransaction.openTemplates(today: LocalDate): List<Template> =
    templates("(generated_through IS NULL OR generated_through < ?)", listOf(today.toString()))

/**
 * Creates [t]'s occurrences up to [today] that don't exist yet (shared instancesToCreate), each
 * with its deterministic id and a copy of the template's checklist, then moves generated_through
 * to the last one. The instances go first: the server checks each PUT against generated_through,
 * and a deleted occurrence (at or before it) is never created again. Writes nothing if nothing is due.
 */
internal fun PowerSyncTransaction.createInstances(t: Template, today: LocalDate, userId: String?) {
    val dates = instancesToCreate(t.rule, t.start, t.generatedThrough, today)
    if (dates.isEmpty()) return
    val checklist = getAll("SELECT id, title, sort_order FROM subtasks WHERE task_id = ?", listOf(t.id.toString())) {
        Triple(it.getString(0)!!, it.getString(1)!!, it.getLong(2) ?: 0L)
    }
    dates.forEach { date ->
        val id = instanceId(t.id, date)
        if (getOptional("SELECT 1 FROM tasks WHERE id = ?", listOf(id.toString())) { true } != null) return@forEach
        val order = get("SELECT coalesce(max(sort_order), 0) + 1 FROM tasks") { it.getLong(0)!! }
        execute(
            "INSERT INTO tasks (id, user_id, list_id, title, notes, due_date, due_time, template_id, occurrence_date, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(id.toString(), userId, t.listId, t.title, t.notes, date.toString(), t.time, t.id.toString(), date.toString(), order),
        )
        checklist.forEach { (subId, title, sort) ->
            execute(
                "INSERT INTO subtasks (id, user_id, task_id, title, done, sort_order) VALUES (?, ?, ?, ?, 0, ?)",
                listOf(uuidV5(id, "subtask:$subId").toString(), userId, id.toString(), title, sort),
            )
        }
    }
    execute("UPDATE tasks SET generated_through = ? WHERE id = ?", listOf(dates.last().toString(), t.id.toString()))
}
