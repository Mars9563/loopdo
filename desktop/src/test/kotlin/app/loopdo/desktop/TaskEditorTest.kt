package app.loopdo.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.client.tasks.SaveScope
import app.loopdo.client.tasks.TaskDetail
import app.loopdo.client.tasks.TaskDraft
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.TaskEditor
import app.loopdo.desktop.ui.TaskEditorUi
import app.loopdo.desktop.ui.parseDay
import app.loopdo.shared.task.RepeatRule
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TaskEditorTest {
    private val today = LocalDate.of(2026, 11, 19)

    class Rec : TaskEditorUi {
        val saves = mutableListOf<Pair<SaveScope, String?>>()
        override fun saveNew(draft: TaskDraft, onSaved: () -> Unit, onError: (String) -> Unit) { saves += SaveScope.ONE to null; onSaved() }
        override fun save(id: String, seriesId: String?, draft: TaskDraft, scope: SaveScope, onSaved: () -> Unit, onError: (String) -> Unit) { saves += scope to seriesId; onSaved() }
        override fun delete(id: String, onDone: () -> Unit) {}
        override fun stopRepeating(templateId: String, onDone: () -> Unit) {}
        override fun addStep(taskId: String, title: String, onDone: () -> Unit, onError: (String) -> Unit) {}
        override fun toggleStep(id: String) {}
        override fun deleteStep(id: String) {}
        override fun newList(name: String, onDone: () -> Unit, onError: (String) -> Unit) {}
    }

    private val copy = TaskDetail("c1", "gym bag", null, today, null, null, null, templateId = "s1", completedOn = null, lateSince = null, generatedThrough = null)

    @Test
    fun `a copy of a series asks, and each answer maps to the phone's scope`() {
        for ((answer, scope) in listOf("[this one]" to SaveScope.ONE, "[this and future]" to SaveScope.SERIES)) runComposeUiTest {
            val rec = Rec()
            setContent { LoopDoTheme { TaskEditor(copy, RepeatRule.Weekly(setOf(DayOfWeek.THURSDAY)), emptyList(), emptyList(), today, rec) {} } }
            onNodeWithText("[save ↵]").performClick()
            assertEquals(0, rec.saves.size, "asks first")
            onNodeWithText(answer).performClick()
            assertEquals<List<Pair<SaveScope, String?>>>(listOf(scope to "s1"), rec.saves)
        }
    }

    @Test
    fun `typed days are understood`() {
        assertEquals(LocalDate.of(2026, 11, 21), parseDay("2026-11-21", today))
        assertEquals(LocalDate.of(2026, 11, 21), parseDay("21 nov", today))
        assertEquals(LocalDate.of(2027, 1, 3), parseDay("3 january", today))
        assertEquals(today.plusDays(1), parseDay("tomorrow", today))
        assertEquals(null, parseDay("someday", today))
    }
}
