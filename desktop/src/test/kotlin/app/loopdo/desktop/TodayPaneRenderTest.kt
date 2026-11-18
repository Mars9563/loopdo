package app.loopdo.desktop

import app.loopdo.client.today.DemoUi
import app.loopdo.client.today.demoToday
import app.loopdo.desktop.ui.Place
import app.loopdo.desktop.ui.Shell
import app.loopdo.desktop.ui.TodayPane
import app.loopdo.desktop.ui.savedStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** D2 Task 4: the today screen renders the locked layout with the phone's demo day (PNG kept for a look). */
class TodayPaneRenderTest {
    @Test
    fun `today renders with the demo day`() {
        val out = File("build/renders/today.png")
        selfCheck(out) {
            Shell(Place.TODAY, {}, savedStatus("all saved · just now")) {
                TodayPane(demoToday(), DemoUi, journal = null, focusLine = "focus › gym 4× · › pay rent by wed", onWrite = {}, onAdd = {})
            }
        }
        assertTrue(out.length() > 10_000)
    }
}
