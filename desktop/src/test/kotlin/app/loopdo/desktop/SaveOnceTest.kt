package app.loopdo.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.client.habits.HabitDraft
import app.loopdo.client.tasks.TaskDraft
import app.loopdo.desktop.ui.HabitEditor
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.client.tasks.SaveScope
import app.loopdo.desktop.ui.TaskEditor
import app.loopdo.desktop.ui.TaskEditorUi
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ultrareview: Enter pressed again (or held) while a save is still running created the habit/task twice. */
@OptIn(ExperimentalTestApi::class)
class SaveOnceTest {
    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun `a new habit is created once however often enter is pressed while it saves`() = runComposeUiTest {
        val saves = mutableListOf<HabitDraft>()
        var fail: ((String) -> Unit)? = null
        // The save never finishes on its own (a busy database): it only records the call.
        setContent { LoopDoTheme { HabitEditor(null, today, false, { d, onError -> saves += d; fail = onError }, {}, {}, {}) } }
        val name = onAllNodes(hasSetTextAction()).onFirst()
        name.performTextInput("read 20 pages")
        name.performKeyInput { pressKey(Key.Enter); pressKey(Key.Enter); pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, saves.size)
        // A refused save frees the button again.
        runOnIdle { fail!!("try again.") }
        name.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(2, saves.size)
    }

    @Test
    fun `a new task, list and step are each created once while they save`() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val ui = object : TaskEditorUi {
            override fun saveNew(draft: TaskDraft, onSaved: () -> Unit, onError: (String) -> Unit) { calls += "task" }
            override fun save(id: String, seriesId: String?, draft: TaskDraft, scope: SaveScope, onSaved: () -> Unit, onError: (String) -> Unit) { calls += "edit" }
            override fun delete(id: String, onDone: () -> Unit) {}
            override fun stopRepeating(templateId: String, onDone: () -> Unit) {}
            override fun addStep(taskId: String, title: String, onDone: () -> Unit, onError: (String) -> Unit) { calls += "step" }
            override fun toggleStep(id: String) {}
            override fun deleteStep(id: String) {}
            override fun newList(name: String, onDone: () -> Unit, onError: (String) -> Unit) { calls += "list" }
        }
        setContent { LoopDoTheme { TaskEditor(null, null, emptyList(), emptyList(), today, ui) {} } }
        val title = onAllNodes(hasSetTextAction()).onFirst()
        title.performTextInput("pay rent")
        title.performKeyInput { pressKey(Key.Enter); pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("task"), calls)
    }
}
