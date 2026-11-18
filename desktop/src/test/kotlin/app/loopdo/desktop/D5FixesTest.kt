package app.loopdo.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.loopdo.client.stats.StatsRows
import app.loopdo.client.stats.buildReview
import app.loopdo.client.stats.buildStats
import app.loopdo.desktop.ui.LoopDoTheme
import app.loopdo.desktop.ui.ReviewPane
import app.loopdo.desktop.ui.StatsPane
import app.loopdo.desktop.ui.StatsMessage
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.stats.PixelMode
import app.loopdo.shared.stats.StatHabit
import app.loopdo.shared.stats.WeekInput
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/** D5 final review, items 1–3. */
@OptIn(ExperimentalTestApi::class)
class D5FixesTest {
    private fun rows(today: LocalDate): StatsRows {
        val h = StatHabit(Habit("g", HabitKind.YES_NO, 1, listOf(HabitSchedule(today.minusDays(60), Recurrence.DAILY, emptySet()))), "gym", 0)
        return StatsRows(emptyList(), WeekInput(listOf(h), mapOf("g" to (1..60L).map { HabitLog(today.minusDays(it), 1) }), emptyList(), emptySet()), emptyMap(), emptySet(), emptyList())
    }

    @Test
    fun `paging back across new year labels the next week with its real number`() = runComposeUiTest {
        val today = LocalDate.of(2027, 1, 10) // a Sunday in week 1 of 2027
        val r = buildReview(rows(today), today, LocalDate.of(2026, 12, 28)) // week 53 of 2026
        setContent { LoopDoTheme { ReviewPane(r, ReviewPaneTest.Rec()) } }
        onNodeWithText("[1 ›]").assertExists()
        onNodeWithText("[54 ›]").assertDoesNotExist()
    }

    @Test
    fun `the forward button is hidden on the newest week`() = runComposeUiTest {
        val today = LocalDate.of(2026, 11, 22)
        setContent { LoopDoTheme { ReviewPane(buildReview(rows(today), today, null), ReviewPaneTest.Rec()) } }
        onNodeWithText("[48 ›]").assertDoesNotExist()
    }

    @Test
    fun `mood vs habits says what the phone says before it has enough days`() = runComposeUiTest {
        val today = LocalDate.of(2026, 11, 19)
        val s = buildStats(rows(today), today, 2026, PixelMode.MOOD, true)
        setContent { LoopDoTheme { StatsPane(s, emptyList(), 0, StatsPaneRenderTest.NoOp) } }
        onNodeWithText("shows after 30 days with a mood.").assertExists()
    }

    @Test
    fun `a stats message stays for its own four seconds`() = runComposeUiTest {
        var dismissed = false
        mainClock.autoAdvance = false
        setContent { LoopDoTheme { StatsMessage("pick at most 2.") { dismissed = true } } }
        mainClock.advanceTimeBy(3_500)
        onNodeWithText("pick at most 2.").assertExists()
        assertTrue(!dismissed)
        mainClock.advanceTimeBy(1_000)
        assertTrue(dismissed)
    }
}
