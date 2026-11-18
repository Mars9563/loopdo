package app.loopdo.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.client.habits.EditorStart
import app.loopdo.client.habits.HabitDraft
import app.loopdo.desktop.ui.HabitEditor
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HabitEditorTest {
    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun `a too-short title shows the phone's message and saves nothing`() = runComposeUiTest {
        val saved = mutableListOf<HabitDraft>()
        setContent { LoopDoTheme { HabitEditor(null, today, false, { d, _ -> saved += d }, {}, {}, {}) } }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("ab")
        onNodeWithText("[save ↵]").performClick()
        onNodeWithText("that title is too short. use at least 3 characters.").assertExists()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a habit with ticks keeps its kind, and a valid edit is saved`() = runComposeUiTest {
        val saved = mutableListOf<HabitDraft>()
        val start = EditorStart(HabitDraft("water", HabitKind.COUNT, 8, "glasses", 1, Recurrence.DAILY, emptySet(), null), todayTarget = 8, hasHistory = true)
        setContent { LoopDoTheme { HabitEditor(start, today, false, { d, _ -> saved += d }, {}, {}, {}) } }
        onNodeWithText("[yes/no]").performClick() // locked: nothing happens
        onNodeWithText("it has ticks, so its kind stays", substring = true).assertExists()
        onNodeWithText("[save ↵]").performClick()
        assertEquals(HabitKind.COUNT, saved.single().kind)
        assertEquals(8, saved.single().target)
    }
}
