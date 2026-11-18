package app.loopdo.desktop

import app.loopdo.client.today.FocusMark
import app.loopdo.client.today.HabitItem
import app.loopdo.client.today.TaskItem
import app.loopdo.desktop.ui.focusLine
import app.loopdo.shared.habit.HabitKind
import kotlin.test.Test
import kotlin.test.assertEquals

class FocusLineTest {
    private fun h(t: String, f: FocusMark?) = HabitItem(t, t, HabitKind.YES_NO, 0, 1, 0, null, focus = f)
    private fun task(t: String, focus: Boolean) = TaskItem(t, t, null, null, null, false, focus = focus)

    @Test
    fun `focus habits show their weekly goal, focus tasks their name`() =
        assertEquals("focus › gym 4× · › pay rent", focusLine(listOf(h("gym", FocusMark(2, 4)), h("water", null)), listOf(task("pay rent", true), task("call", false))))

    @Test
    fun `no focus, no line`() = assertEquals("", focusLine(listOf(h("gym", null)), emptyList()))
}
