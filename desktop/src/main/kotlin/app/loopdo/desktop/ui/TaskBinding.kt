package app.loopdo.desktop.ui

import app.loopdo.client.tasks.SaveScope
import app.loopdo.client.tasks.TaskDraft
import app.loopdo.client.tasks.TasksViewModel
import app.loopdo.client.today.TodayViewModel
import java.time.LocalDate

/** The phone's view models behind the laptop's task editor: new tasks like today's add sheet, edits like the task screen. */
class TaskBinding(private val today: TodayViewModel, private val tasks: TasksViewModel, private val day: () -> LocalDate) : TaskEditorUi {
    override fun saveNew(draft: TaskDraft, onSaved: () -> Unit, onError: (String) -> Unit) =
        today.addTask(draft.title, draft.due, draft.time, draft.listId?.toString(), null, day(), draft.repeat, onSaved)
    override fun save(id: String, seriesId: String?, draft: TaskDraft, scope: SaveScope, onSaved: () -> Unit, onError: (String) -> Unit) {
        tasks.save(id, seriesId, draft, scope, onSaved, onError)
    }
    override fun delete(id: String, onDone: () -> Unit) { tasks.delete(id, onDone) }
    override fun stopRepeating(templateId: String, onDone: () -> Unit) { tasks.stopRepeating(templateId, onDone) }
    override fun addStep(taskId: String, title: String, onDone: () -> Unit, onError: (String) -> Unit) { tasks.addSubtask(taskId, title, onDone, onError) }
    override fun toggleStep(id: String) { tasks.toggleSubtask(id) }
    override fun deleteStep(id: String) { tasks.deleteSubtask(id) }
    override fun newList(name: String, onDone: () -> Unit, onError: (String) -> Unit) { tasks.saveList(null, name, onDone, onError) }
}
