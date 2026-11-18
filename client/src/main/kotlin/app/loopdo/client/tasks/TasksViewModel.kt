package app.loopdo.client.tasks

import app.loopdo.client.tasks.SaveScope
import app.loopdo.client.tasks.TaskCommands
import app.loopdo.client.tasks.TaskDraft
import app.loopdo.client.tasks.TemplateRow
import app.loopdo.client.tasks.template

import app.loopdo.client.log.logW
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.loopdo.shared.task.RepeatRule
import app.loopdo.shared.task.parseRepeatRule
import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** One task as the editor needs it. A template has [repeat] set; an occurrence has [templateId]. */
data class TaskDetail(
    val id: String,
    val title: String,
    val notes: String?,
    val due: LocalDate?,
    val time: LocalTime?,
    val listId: String?,
    val repeat: RepeatRule?,
    val templateId: String?,
    val completedOn: LocalDate?,
    /** Server-owned: the first day this task was late, kept when it was moved. */
    val lateSince: LocalDate?,
    val generatedThrough: LocalDate?,
) {
    val isTemplate: Boolean get() = repeat != null
    val isInstance: Boolean get() = templateId != null
}

data class Subtask(val id: String, val title: String, val done: Boolean)

/** A list with its open tasks; [id] null = the inbox (tasks without a list). */
data class ListInfo(val id: String?, val name: String, val open: Int)

private fun SqlCursor.date(i: Int) = getString(i)?.let { LocalDate.parse(it.take(10)) }

/** Watched reads for the task screens (the today screen keeps its own TodayData). */
class TaskData(private val db: PowerSyncDatabase) {
    private val columns = "id, title, notes, due_date, due_time, list_id, repeat_rule, template_id, completed_on, late_since, generated_through"
    private fun detail(c: SqlCursor) = TaskDetail(
        c.getString(0)!!, c.getString(1)!!, c.getString(2), c.date(3), c.getString(4)?.let(LocalTime::parse), c.getString(5),
        c.getString(6)?.let(::parseRepeatRule), c.getString(7), c.date(8), c.date(9), c.date(10),
    )

    fun task(id: String): Flow<TaskDetail?> = db.watch("SELECT $columns FROM tasks WHERE id = ?", listOf(id)) { detail(it) }.map { it.firstOrNull() }

    fun subtasks(taskId: String): Flow<List<Subtask>> =
        db.watch("SELECT id, title, done FROM subtasks WHERE task_id = ? ORDER BY sort_order, title", listOf(taskId)) {
            Subtask(it.getString(0)!!, it.getString(1)!!, (it.getLong(2) ?: 0L) != 0L)
        }

    /** The inbox first, then every list in its order, each with its open (not done, not template) tasks. */
    fun lists(): Flow<List<ListInfo>> = db.watch(
        """SELECT NULL, 'inbox', (SELECT count(*) FROM tasks WHERE list_id IS NULL AND completed_at IS NULL AND repeat_rule IS NULL), -1
           UNION ALL
           SELECT l.id, l.name, (SELECT count(*) FROM tasks t WHERE t.list_id = l.id AND t.completed_at IS NULL AND t.repeat_rule IS NULL), l.sort_order FROM lists l
           ORDER BY 4, 2""",
    ) { ListInfo(it.getString(0), it.getString(1)!!, (it.getLong(2) ?: 0L).toInt()) }

    fun templates(): Flow<List<TemplateRow>> =
        db.watch("SELECT id, title, repeat_rule, due_date, generated_through FROM tasks WHERE repeat_rule IS NOT NULL") { c ->
            val rule = c.getString(2)?.let(::parseRepeatRule)
            val start = c.date(3)
            listOfNotNull(if (rule == null || start == null) null else TemplateRow(c.getString(0)!!, c.getString(1)!!, rule, start, c.date(4)))
        }.map { it.flatten() }
}

class TasksViewModel(
    private val data: TaskData,
    private val actions: TaskCommands,
) : ViewModel() {
    val lists: StateFlow<List<ListInfo>> = data.lists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val templates: StateFlow<List<TemplateRow>> = data.templates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun task(id: String): Flow<TaskDetail?> = data.task(id)
    fun subtasks(id: String): Flow<List<Subtask>> = data.subtasks(id)

    data class Snack(val text: String, val id: Long = System.nanoTime())
    private val _message = MutableStateFlow<Snack?>(null)
    val message: StateFlow<Snack?> = _message.asStateFlow()
    fun dismiss(id: Long) { _message.compareAndSet(_message.value?.takeIf { it.id == id }, null) }
    private fun show(text: String) { _message.value = Snack(text) }

    /** One action; a rule's refusal (plain words) goes to [onError] and nothing was written. */
    private fun run(done: String? = null, onError: (String) -> Unit = ::show, onDone: () -> Unit = {}, work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work(); done?.let(::show); onDone()
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: GENERIC)
            } catch (e: IllegalStateException) {
                onError(e.message ?: GENERIC)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("loopdo_tasks", "task action failed", e)
                onError(GENERIC)
            }
        }
    }

    /** [SaveScope.ONE] edits this task; [SaveScope.SERIES] edits [seriesId] from today on. */
    fun save(id: String, seriesId: String?, draft: TaskDraft, scope: SaveScope, onSaved: () -> Unit, onError: (String) -> Unit) = run(onError = onError, onDone = onSaved) {
        if (scope == SaveScope.SERIES && seriesId != null) actions.editSeries(UUID.fromString(seriesId), draft)
        else actions.saveTask(UUID.fromString(id), draft.copy(repeat = draft.repeat.takeIf { scope == SaveScope.ONE }))
    }

    fun move(id: String, due: LocalDate?) = run("moved.") { actions.moveTask(UUID.fromString(id), due, null) }
    fun moveToList(id: String, listId: String?, name: String) = run("moved to # $name.") { actions.moveToList(UUID.fromString(id), listId?.let(UUID::fromString)) }
    fun delete(id: String, onDone: () -> Unit = {}) = run("deleted.", onDone = onDone) { actions.deleteTask(UUID.fromString(id)) }
    fun stopRepeating(templateId: String, onDone: () -> Unit) = run("it won't repeat any more. the ones already made stay.", onDone = onDone) { actions.stopRepeating(UUID.fromString(templateId)) }
    fun addSubtask(taskId: String, title: String, onDone: () -> Unit, onError: (String) -> Unit) = run(onError = onError, onDone = onDone) { actions.addSubtask(UUID.fromString(taskId), title) }
    fun toggleSubtask(id: String) = run { actions.toggleSubtask(UUID.fromString(id)) }
    fun deleteSubtask(id: String) = run { actions.deleteSubtask(UUID.fromString(id)) }
    fun saveList(id: String?, name: String, onDone: () -> Unit, onError: (String) -> Unit) = run(onError = onError, onDone = onDone) { actions.saveList(id?.let(UUID::fromString), name) }
    fun deleteList(id: String, onDone: () -> Unit) = run("list deleted. its tasks are in the inbox.", onDone = onDone) { actions.deleteList(UUID.fromString(id)) }

    private companion object {
        const val GENERIC = "couldn't save that. try again."
    }
}
