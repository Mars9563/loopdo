package app.loopdo.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.client.tasks.TaskDetail
import app.loopdo.client.tasks.TaskDraft
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.TaskBinding
import app.loopdo.desktop.ui.TaskEditor
import app.loopdo.desktop.ui.sheetEsc
import app.loopdo.shared.task.RepeatRule
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D4 final review, items 1–4. */
@OptIn(ExperimentalTestApi::class)
class D4FixesTest {
    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun `esc closes an open sheet even when no field has focus`() = runComposeUiTest {
        var closed = false
        val fr = FocusRequester()
        setContent { Box(Modifier.focusRequester(fr).focusable().sheetEsc(open = true) { closed = true }) {} }
        runOnIdle { fr.requestFocus() }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertTrue(closed) }
    }

    @Test
    fun `the task editor opens with the cursor in its title`() = runComposeUiTest {
        setContent { LoopDoTheme { TaskEditor(null, null, emptyList(), emptyList(), today, TaskEditorTest.Rec()) {} } }
        waitForIdle()
        onAllNodes(hasSetTextAction()).onFirst().assert(isFocused())
    }

    @Test
    fun `a day in the past is refused, today's and an unchanged day are fine`() = runComposeUiTest {
        val rec = TaskEditorTest.Rec()
        setContent { LoopDoTheme { TaskEditor(null, null, emptyList(), emptyList(), today, rec) {} } }
        val fields = onAllNodes(hasSetTextAction())
        fields[0].performTextInput("call bank")
        fields[1].performTextInput("2026-11-01")
        onNodeWithText("[save ↵]").performClick()
        onNodeWithText("pick today or a later day.").assertExists()
        assertTrue(rec.saves.isEmpty())
    }

    @Test
    fun `a new task keeps its note`() {
        var got: TaskDraft? = null
        val b = TaskBinding(create = { d, ok, _ -> got = d; ok() })
        b.saveNew(TaskDraft("pay rent", "landlord asked", today, null, null, null), {}, {})
        assertEquals("landlord asked", got?.notes)
    }

    @Test
    fun `a series rule that loads late is adopted`() = runComposeUiTest {
        val copy = TaskDetail("c1", "gym bag", null, today, null, null, null, templateId = "s1", completedOn = null, lateSince = null, generatedThrough = null)
        val rule = mutableStateOf<RepeatRule?>(null)
        setContent { LoopDoTheme { TaskEditor(copy, rule.value, emptyList(), emptyList(), today, TaskEditorTest.Rec()) {} } }
        runOnIdle { rule.value = RepeatRule.Weekly(setOf(DayOfWeek.THURSDAY)) }
        waitForIdle()
        onNodeWithText("[th]").assertExists() // the weekly day row shows: the rule arrived
    }
}
