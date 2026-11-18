package app.loopdo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.desktop.ui.placeKeys
import app.loopdo.desktop.ui.typingField
import app.loopdo.desktop.ui.rootKeys
import androidx.compose.ui.test.onRoot
import kotlin.test.Test
import kotlin.test.assertTrue

/** The rail's letters never fire while you type (they used to catch "t", "h", "j", "s" before the writer saw them). */
@OptIn(ExperimentalTestApi::class)
class PlaceKeysTest {
    @Test
    fun `typing in a field doesn't switch places`() = runComposeUiTest {
        val pressed = mutableListOf<Char>()
        setContent {
            var text by remember { mutableStateOf("") }
            Box(Modifier.placeKeys(blocked = false) { pressed += it }) { BasicTextField(text, { text = it }, Modifier.typingField()) }
        }
        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performKeyInput { listOf(Key.T, Key.H, Key.J, Key.S).forEach { pressKey(it) } }
        assertTrue(pressed.isEmpty(), "keys fired while typing: $pressed")
    }

    @Test
    fun `with nothing typed, the letters switch places`() = runComposeUiTest {
        val pressed = mutableListOf<Char>()
        val fr = androidx.compose.ui.focus.FocusRequester()
        setContent { Box(Modifier.rootKeys(fr, blocked = false) { pressed += it }) {} }
        runOnIdle { fr.requestFocus() }
        onRoot().performKeyInput { pressKey(Key.H) }
        runOnIdle { assertTrue(pressed == listOf('h'), "got $pressed") }
    }
}
