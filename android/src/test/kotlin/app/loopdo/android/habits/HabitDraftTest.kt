package app.loopdo.android.habits

import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek

/** The habit editor's checks (owner's rules, 2026-11-17): what is refused, with which message, and how a draft is cleaned. */
class HabitDraftTest {
    private val vocab = HabitDraft(
        title = "japanese vocab", kind = HabitKind.COUNT, target = 35, unit = "words", step = 5,
        recurrence = Recurrence.DAILY, weekdays = emptySet(), reminderTime = null,
    )
    private val gym = vocab.copy(title = "gym", kind = HabitKind.YES_NO, target = 1, unit = null, step = 1)

    private fun refused(d: HabitDraft): String {
        try { checkDraft(d) } catch (e: IllegalArgumentException) { return e.message!! }
        fail("expected the draft to be refused: $d")
        error("unreachable")
    }

    @Test
    fun titleIsRequiredEvenIfOnlySpaces() {
        assertEquals("why don't you pick a title for the habit.", refused(vocab.copy(title = "")))
        assertEquals("why don't you pick a title for the habit.", refused(vocab.copy(title = "    ")))
    }

    @Test
    fun titleIsThreeToThirtyCharactersAfterTrimming() {
        assertEquals("that title is too short. use at least 3 characters.", refused(vocab.copy(title = " ab ")))
        assertEquals("that title is too long. keep it under 30 characters.", refused(vocab.copy(title = "a".repeat(31))))
        assertEquals("gym", checkDraft(gym.copy(title = "  gym  ")).title)
        assertEquals("a".repeat(30), checkDraft(vocab.copy(title = "a".repeat(30))).title)
    }

    @Test
    fun countTargetIsAtLeastTwo() {
        assertEquals("if the habit just needs one step why complicate things keep it a yes/no habit.", refused(vocab.copy(target = 1)))
        assertEquals("if the habit just needs one step why complicate things keep it a yes/no habit.", refused(vocab.copy(target = 0)))
        assertEquals(2, checkDraft(vocab.copy(target = 2)).target)
    }

    @Test
    fun stepIsAtLeastOne() {
        assertEquals("my friend n+0 = n.", refused(vocab.copy(step = 0)))
        assertEquals("my friend n+0 = n.", refused(vocab.copy(step = -5)))
    }

    @Test
    fun unitIsOptionalAndShort() {
        assertNull(checkDraft(vocab.copy(unit = "   ")).unit)
        assertEquals("pages", checkDraft(vocab.copy(unit = " pages ")).unit)
        assertEquals("keep the unit under 16 characters, like \"words\" or \"pages\".", refused(vocab.copy(unit = "a".repeat(17))))
    }

    @Test
    fun yesNoHabitDropsCountSettings() {
        val d = checkDraft(gym.copy(target = 7, step = 3, unit = "reps"))
        assertEquals(listOf(1, 1), listOf(d.target, d.step))
        assertNull(d.unit)
    }

    @Test
    fun weekdaysNeedAtLeastOneDayAndDailyHasNone() {
        assertEquals("pick at least one weekday.", refused(gym.copy(recurrence = Recurrence.WEEKDAYS, weekdays = emptySet())))
        val daily = checkDraft(gym.copy(recurrence = Recurrence.DAILY, weekdays = setOf(DayOfWeek.MONDAY)))
        assertEquals(emptySet<DayOfWeek>(), daily.weekdays) // ticks left over from switching to daily
    }

    @Test
    fun aPauseIsNotAnEdit() {
        assertEquals("use [pause] to pause a habit.", refused(gym.copy(recurrence = Recurrence.PAUSED)))
    }
}
