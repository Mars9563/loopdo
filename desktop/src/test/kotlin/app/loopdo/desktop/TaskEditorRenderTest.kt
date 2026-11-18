package app.loopdo.desktop

import androidx.compose.foundation.layout.Box
import app.loopdo.client.tasks.ListInfo
import app.loopdo.client.tasks.Subtask
import app.loopdo.client.tasks.TaskDetail
import app.loopdo.client.today.DemoUi
import app.loopdo.client.today.demoToday
import app.loopdo.desktop.ui.Place
import app.loopdo.desktop.ui.Shell
import app.loopdo.desktop.ui.TaskEditor
import app.loopdo.desktop.ui.TodayPane
import app.loopdo.desktop.ui.savedStatus
import app.loopdo.shared.task.RepeatRule
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

class TaskEditorRenderTest {
    @Test
    fun `task editor renders over today`() {
        val d = LocalDate.of(2026, 11, 14)
        val detail = TaskDetail("t1", "pay rent", "landlord asked for it before the 5th", d, null, "home", RepeatRule.MonthlyOnDay(1), null, null, null, null)
        val out = File("build/renders/task-editor.png")
        selfCheck(out) {
            Box {
                Shell(Place.TODAY, {}, savedStatus("all saved · just now")) { TodayPane(demoToday(), DemoUi, null, "", {}, {}) }
                TaskEditor(detail, null, listOf(Subtask("s1", "open bank app", true), Subtask("s2", "transfer", false)),
                    listOf(ListInfo(null, "inbox", 3), ListInfo("home", "home", 2), ListInfo("work", "work", 3)), d, TaskEditorTest.Rec()) {}
            }
        }
        assertTrue(out.length() > 10_000)
    }
}
