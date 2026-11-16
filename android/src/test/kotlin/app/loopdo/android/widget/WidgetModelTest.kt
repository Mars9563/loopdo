package app.loopdo.android.widget

import app.loopdo.android.onboarding.StartState
import app.loopdo.android.today.TodayFixture
import app.loopdo.android.today.TodayFixture.d
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WidgetModelTest {
    private val today = TodayFixture.today()
    private val m = widgetModel(today, TodayFixture.dayEndAt)

    @Test
    fun habitsOpenThenDoneWithTheRightColumn() {
        assertEquals(listOf("water", "gym", "meditate", "japanese 20m"), m.habits.map { it.title })
        val (water, gym, med, jp) = m.habits
        assertEquals(WidgetRow("water", true, "water", false, "5/8", Tone.TEXT, plusStep = 1), water)
        assertEquals(WidgetRow("gym", true, "gym", false, "@18:00", Tone.DIM, plusStep = null), gym)
        val streaks = today.habitsDone.associate { it.id to it.streak }
        assertEquals(WidgetRow("med", true, "meditate", true, "${streaks["med"]}d", Tone.TEXT, plusStep = null), med)
        assertTrue(jp.done)
        assertEquals(2 to 4, m.habitsDone to m.habitsTotal)
    }

    @Test
    fun tasksLateFirstThenTodayThenDone() {
        assertEquals(
            listOf(
                WidgetRow("rent", false, "pay rent", false, "!late 13nov", Tone.WARN, plusStep = null),
                WidgetRow("apply", false, "apply: 2 jobs", false, "", Tone.DIM, plusStep = null), // no list tag in the widget
                WidgetRow("call", false, "call home", true, "", Tone.DIM, plusStep = null),
            ),
            m.tasks,
        )
        assertEquals(2, m.tasksOpen)
    }

    @Test
    fun headerAndJournalDot() {
        assertEquals("sat 14 nov", m.dateLabel)
        assertEquals("3h18m", m.countdown)
        assertEquals(false, m.journalWritten)
        assertEquals(true, widgetModel(TodayFixture.today(journalDays = setOf(d(14))), TodayFixture.dayEndAt).journalWritten)
    }

    @Test
    fun notReadyWidgetHasNoTicks() {
        assertEquals(WidgetState.SignedOut, widgetState(StartState.SignedOut, today, TodayFixture.dayEndAt))
        assertEquals(WidgetState.Restoring, widgetState(StartState.SyncingFirstTime, today, TodayFixture.dayEndAt))
        assertEquals(WidgetState.Restoring, widgetState(StartState.Loading, today, TodayFixture.dayEndAt))
        assertEquals(WidgetState.NeedsSetup, widgetState(StartState.NeedsOnboarding, today, TodayFixture.dayEndAt))
        assertEquals(WidgetState.Restoring, widgetState(StartState.Ready, null, null))
        assertIs<WidgetState.Ready>(widgetState(StartState.Ready, today, TodayFixture.dayEndAt))
    }
}
