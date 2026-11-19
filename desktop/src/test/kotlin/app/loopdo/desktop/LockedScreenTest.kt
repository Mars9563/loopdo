package app.loopdo.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.desktop.lock.PinLock
import app.loopdo.desktop.store.FileKeyValue
import app.loopdo.desktop.ui.LockedScreen
import app.loopdo.desktop.ui.LoopDoTheme
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LockedScreenTest {
    private var now = Instant.parse("2026-11-19T08:00:00Z")
    private fun locked(): PinLock {
        val l = PinLock(FileKeyValue(File(Files.createTempDirectory("loopdo-pin").toFile(), "lock.json"))) { now }
        l.set("4821"); now = now.plus(Duration.ofHours(2)); l.windowOpened()
        return l
    }

    @Test
    fun `typing the 4 right digits opens it, wrong ones clear`() = runComposeUiTest {
        val l = locked()
        setContent { LoopDoTheme { LockedScreen(l) { true } } }
        onNode(hasSetTextAction()).performTextInput("1111")
        waitForIdle()
        assertTrue(l.needed.value)
        onNodeWithText("wrong pin · 4 tries left").assertExists()
        assertEquals(0, onAllNodes(hasText("●")).fetchSemanticsNodes().size, "cleared")
        onNode(hasSetTextAction()).performTextInput("4821")
        waitForIdle()
        assertFalse(l.needed.value, "opens on the 4th digit, no enter")
    }

    @Test
    fun `forgot signs out`() = runComposeUiTest {
        var forgot = 0
        setContent { LoopDoTheme { LockedScreen(locked()) { forgot++; true } } }
        onNodeWithText("[forgot? sign in again]").performClick()
        waitForIdle()
        assertEquals(1, forgot)
    }

    @Test
    fun `forgot while offline keeps the lock and says why`() = runComposeUiTest {
        val l = locked()
        var calls = 0
        setContent { LoopDoTheme { LockedScreen(l, onForgot = { calls++; false }) } }
        onNodeWithText("[forgot? sign in again]").performClick()
        waitForIdle()
        assertEquals(1, calls)
        assertTrue(l.isSet && l.needed.value, "a failed sign-out never removes the pin (that would open the app)")
        onNodeWithText("signing out needs the internet. try again when you're online.").assertExists()
    }

    @Test
    fun `forgot that signed out removes the pin`() = runComposeUiTest {
        val l = locked()
        setContent { LoopDoTheme { LockedScreen(l, onForgot = { true }) } }
        onNodeWithText("[forgot? sign in again]").performClick()
        waitForIdle()
        assertFalse(l.isSet)
    }
}
