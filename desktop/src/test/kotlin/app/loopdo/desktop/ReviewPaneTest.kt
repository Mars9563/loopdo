package app.loopdo.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.client.stats.PickItem
import app.loopdo.client.stats.StatsRows
import app.loopdo.client.stats.buildReview
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.ReviewActions
import app.loopdo.desktop.ui.ReviewPane
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.stats.StatHabit
import app.loopdo.shared.stats.WeekInput
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReviewPaneTest {
    private fun rows(today: LocalDate): StatsRows {
        val since = today.minusDays(40)
        val h = StatHabit(Habit("g", HabitKind.YES_NO, 1, listOf(HabitSchedule(since, Recurrence.DAILY, emptySet()))), "gym", 0)
        return StatsRows(emptyList(), WeekInput(listOf(h), mapOf("g" to (1..40L).map { HabitLog(today.minusDays(it), 1) }), emptyList(), emptySet()), emptyMap(), emptySet(), emptyList())
    }

    class Rec : ReviewActions {
        val added = mutableListOf<Pair<PickItem, Int?>>()
        override fun page(by: Int) {}
        override fun close() {}
        override fun addFocus(item: PickItem, goal: Int?) { added += item to goal }
        override fun removeFocus(id: String) {}
    }

    @Test
    fun `on sunday a habit can be picked as next week's focus with its goal`() = runComposeUiTest {
        val sunday = LocalDate.of(2026, 11, 22)
        val r = buildReview(rows(sunday), sunday, null)
        assertTrue(r.canEdit, "sunday is a focus day")
        val rec = Rec()
        setContent { LoopDoTheme { ReviewPane(r, rec) } }
        onNodeWithText("[+ focus]").performClick()
        onNodeWithText("[−]").performClick()
        onAllNodes(androidx.compose.ui.test.hasText("gym")).onLast().performClick() // the picker row (the first "gym" is the week grid)
        assertEquals("g", rec.added.single().first.itemId)
        assertEquals((r.picker.first().goal ?: 0) - 1, rec.added.single().second, "a daily habit starts at 7; [−] makes it 6")
        selfCheck(File("build/renders/review.png")) { ReviewPane(r, Rec()) }
    }

    @Test
    fun `on a wednesday the focus can't be changed`() = runComposeUiTest {
        val wed = LocalDate.of(2026, 11, 18)
        val r = buildReview(rows(wed), wed, null)
        val rec = Rec()
        setContent { LoopDoTheme { ReviewPane(r, rec) } }
        onNodeWithText("[+ focus]").performClick()
        onNodeWithText("times next week").assertDoesNotExist()
        assertTrue(rec.added.isEmpty())
    }
}
