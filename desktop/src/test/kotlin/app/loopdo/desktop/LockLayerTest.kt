package app.loopdo.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.desktop.ui.LockLayer
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.TermField
import kotlin.test.Test
import kotlin.test.assertEquals

/** D8 review I2: the lock covers the app instead of throwing it away (an open editor's text survives). */
@OptIn(ExperimentalTestApi::class)
class LockLayerTest {
    @Test
    fun `what was typed in the app survives a lock and an unlock`() = runComposeUiTest {
        var locked by mutableStateOf(false)
        setContent {
            LoopDoTheme {
                LockLayer(locked, lockScreen = { BasicText("~/locked") }) {
                    var note by remember { mutableStateOf("") }
                    TermField(note, { note = it })
                }
            }
        }
        onNode(hasSetTextAction()).performTextInput("landlord asked before the 5th")
        locked = true; waitForIdle()
        onNodeWithText("~/locked").assertExists()
        locked = false; waitForIdle()
        onNode(hasText("landlord asked before the 5th")).assertExists()
    }

    @Test
    fun `no key reaches the app while it is locked`() = runComposeUiTest {
        var locked by mutableStateOf(false)
        var keys = 0
        setContent {
            LoopDoTheme {
                LockLayer(locked, lockScreen = { BasicText("~/locked") }) {
                    TermField("", {}, Modifier.onKeyEvent { keys++; false })
                }
            }
        }
        val field = onNode(hasSetTextAction())
        field.performKeyInput { pressKey(Key.T) }
        waitForIdle()
        val before = keys
        locked = true; waitForIdle()
        field.performKeyInput { pressKey(Key.T); pressKey(Key.J) }
        waitForIdle()
        assertEquals(before, keys, "rail letters and typing stay out while locked")
    }
}
