package app.loopdo.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.Sheet
import app.loopdo.desktop.ui.TermField
import kotlin.test.Test
import kotlin.test.assertEquals

/** Owner, 2026-11-19: a space typed in the task editor closed it (the backdrop took Space as "click outside"). */
@OptIn(ExperimentalTestApi::class)
class SheetKeysTest {
    @Test
    fun `space and enter typed in a sheet's field don't close the sheet, esc does`() = runComposeUiTest {
        var closed = 0
        setContent {
            LoopDoTheme {
                Sheet("new task", onClose = { closed++ }) {
                    var text by remember { mutableStateOf("") }
                    TermField(text, { text = it })
                }
            }
        }
        val field = onAllNodes(hasSetTextAction()).onFirst()
        field.performClick()
        field.performKeyInput { pressKey(Key.Spacebar); pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(0, closed, "space is a space")
        field.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(1, closed, "esc still closes")
    }

    @Test
    fun `a click inside the sheet stays, the esc link closes`() = runComposeUiTest {
        var closed = 0
        setContent { LoopDoTheme { Sheet("new task", onClose = { closed++ }) { TermField("", {}) } } }
        onNodeWithText("new task").performClick()
        waitForIdle()
        assertEquals(0, closed)
        onNodeWithText("[esc]").performClick()
        waitForIdle()
        assertEquals(1, closed)
    }
}
