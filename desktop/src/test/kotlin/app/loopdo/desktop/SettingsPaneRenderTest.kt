package app.loopdo.desktop

import app.loopdo.client.settings.SettingsUi
import app.loopdo.desktop.ui.LaptopSettings
import app.loopdo.desktop.ui.Place
import app.loopdo.desktop.ui.SettingsActions
import app.loopdo.desktop.ui.SettingsPane
import app.loopdo.desktop.ui.Shell
import app.loopdo.desktop.ui.savedStatus
import app.loopdo.shared.remind.ReminderPrefs
import java.io.File
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

/** D8: ~/settings renders as locked (desktop-screens-v1) + the pin lock row (PNG kept for a look). */
class SettingsPaneRenderTest {
    @Test
    fun `settings renders`() {
        val out = File("build/renders/settings.png")
        val ui = SettingsUi("all saved" to "last synced 20:15", false, LocalTime.of(4, 0), ZoneId.of("Asia/Kolkata"), null, null, "kaze", ReminderPrefs(), null, "1.0.0")
        val act = object : SettingsActions {
            override fun rename(name: String) {}; override fun setPrefs(p: ReminderPrefs) {}; override fun setPings(on: Boolean) {}
            override fun setAutostart(on: Boolean) {}; override fun setPin(pin: String) {}; override fun checkPin(pin: String) = false
            override fun clearPin() {}; override fun signOut() {}
        }
        selfCheck(out) {
            Shell(Place.SETTINGS, {}, savedStatus("all saved · just now")) {
                SettingsPane(ui, LaptopSettings(pingsOn = true, autostartOn = true, pinOn = true, email = "you@example.com"), act)
            }
        }
        assertTrue(out.length() > 10_000)
    }
}
