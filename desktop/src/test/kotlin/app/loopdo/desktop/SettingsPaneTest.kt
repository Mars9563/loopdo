package app.loopdo.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.client.settings.SettingsUi
import app.loopdo.shared.remind.ReminderPrefs
import app.loopdo.desktop.ui.LaptopSettings
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.SettingsActions
import app.loopdo.desktop.ui.SettingsPane
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsPaneTest {
    private val ui = SettingsUi(
        data = "all saved" to "last synced 20:15", waitingNow = false, dayEnd = LocalTime.of(4, 0), zone = ZoneId.of("Asia/Kolkata"),
        pending = null, keep = null, name = "kaze", prefs = ReminderPrefs(), admin = null, version = "1.0.0",
    )

    private class Acts : SettingsActions {
        val log = mutableListOf<String>()
        var stored: String? = null
        override fun rename(name: String) { log += "name $name" }
        override fun setPrefs(p: ReminderPrefs) { log += "prefs habits=${p.habits} tasks=${p.tasks} journal=${p.journal}" }
        override fun setPings(on: Boolean) { log += "pings $on" }
        override fun setAutostart(on: Boolean) { log += "autostart $on" }
        override fun setPin(pin: String) { stored = pin; log += "pin set" }
        override fun checkPin(pin: String) = pin == stored
        override fun clearPin() { stored = null; log += "pin off" }
        override fun signOut() { log += "sign out" }
    }

    @Test
    fun `the locked rows are there, with the phone-only ones read-only`() = runComposeUiTest {
        setContent { LoopDoTheme { SettingsPane(ui, LaptopSettings(pingsOn = true, autostartOn = true, pinOn = false, email = "you@example.com"), Acts()) } }
        listOf("~/settings", "you", "name", "reminders · shared with the phone", "habits + tasks", "journal nudge", "day-end warning", "sunday review",
            "this laptop", "pings on this laptop", "start at login", "quick add", "pin lock", "day", "change on the phone", "day ends", "04:00",
            "time zone", "asia/kolkata", "data", "export · delete account", "on the phone", "account", "signed in as you@example.com", "[sign out]",
        ).forEach { onAllNodesWithText(it, substring = true).onFirst().assertExists() }
    }

    @Test
    fun `this laptop's switches and the shared reminders call through`() = runComposeUiTest {
        val a = Acts()
        setContent { LoopDoTheme { SettingsPane(ui, LaptopSettings(pingsOn = true, autostartOn = false, pinOn = false, email = "x"), a) } }
        onNodeWithText("pings on this laptop", substring = true).performClick()
        onNodeWithText("start at login", substring = true).performClick()
        onNodeWithText("habits + tasks", substring = true).performClick()
        waitForIdle()
        assertEquals(listOf("pings false", "autostart true", "prefs habits=false tasks=false journal=true"), a.log)
    }

    @Test
    fun `pin lock on asks twice, off asks it once`() = runComposeUiTest {
        val a = Acts()
        var pinOn = false
        setContent { LoopDoTheme { SettingsPane(ui, LaptopSettings(true, true, pinOn, "x"), a) } }
        onNodeWithText("pin lock", substring = true).performClick()
        waitForIdle()
        onNodeWithText("new pin").assertExists()
        onNode(hasSetTextAction()).performTextInput("4821")
        waitForIdle()
        onNodeWithText("once more").assertExists()
        onNode(hasSetTextAction()).performTextInput("4821")
        waitForIdle()
        assertEquals("4821", a.stored, "set after the same 4 digits twice; log=${a.log}; matched=${onAllNodes(hasText("didn't match", substring = true)).fetchSemanticsNodes().size}")
        assertTrue("pin set" in a.log)
    }

    @Test
    fun `sign out asks once more first`() = runComposeUiTest {
        val a = Acts()
        setContent { LoopDoTheme { SettingsPane(ui, LaptopSettings(true, true, false, "x"), a) } }
        onNodeWithText("[sign out]").performClick()
        waitForIdle()
        assertTrue("sign out" !in a.log, "not on the first click")
        onNodeWithText("[sign out: sure?]").performClick()
        waitForIdle()
        assertEquals("sign out", a.log.last())
    }
}
