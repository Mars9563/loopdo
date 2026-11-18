package app.loopdo.desktop.ui

import app.loopdo.client.tasks.SaveScope
import app.loopdo.client.tasks.TaskDraft
import app.loopdo.client.tasks.TasksViewModel

/**
 * The phone's task code behind the laptop's task editor. A new task is made with [create] (TaskActions.saveTask(null, …),
 * like a repeating one on the phone), so its note, list and repeat are all kept and a refusal shows in the sheet.
 */
class TaskBinding(
    private val tasks: TasksViewModel? = null,
    private val create: (TaskDraft, () -> Unit, (String) -> Unit) -> Unit,
) : TaskEditorUi {
    private val vm get() = tasks!!
    override fun saveNew(draft: TaskDraft, onSaved: () -> Unit, onError: (String) -> Unit) = create(draft, onSaved, onError)
    override fun save(id: String, seriesId: String?, draft: TaskDraft, scope: SaveScope, onSaved: () -> Unit, onError: (String) -> Unit) {
        vm.save(id, seriesId, draft, scope, onSaved, onError)
    }
    override fun delete(id: String, onDone: () -> Unit) { vm.delete(id, onDone) }
    override fun stopRepeating(templateId: String, onDone: () -> Unit) { vm.stopRepeating(templateId, onDone) }
    override fun addStep(taskId: String, title: String, onDone: () -> Unit, onError: (String) -> Unit) { vm.addSubtask(taskId, title, onDone, onError) }
    override fun toggleStep(id: String) { vm.toggleSubtask(id) }
    override fun deleteStep(id: String) { vm.deleteSubtask(id) }
    override fun newList(name: String, onDone: () -> Unit, onError: (String) -> Unit) { vm.saveList(null, name, onDone, onError) }
}
