package app.loopdo.android.stats

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.stats.Focus
import app.loopdo.shared.stats.FocusKind
import app.loopdo.shared.stats.PixelMode
import app.loopdo.shared.stats.StatHabit
import app.loopdo.shared.stats.StatTask
import app.loopdo.shared.stats.WeekInput
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stats tab and the weekly review as pure models (M8). Week 47 = mon 16 – sun 22 nov 2026. */
class StatsModelTest {
    private val versions = listOf(DaySettingsVersion(Instant.EPOCH, DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0)), null))
    private val w47 = LocalDate.of(2026, 11, 16)
    private val w48 = LocalDate.of(2026, 11, 23)
    private val gym = Habit("gym", HabitKind.YES_NO, 1, listOf(HabitSchedule(LocalDate.of(2026, 11, 1), Recurrence.DAILY)))
    private val focus47 = Focus("f1", w47, FocusKind.HABIT, "gym", "gym", 4, null)
    private val focus48 = Focus("f2", w48, FocusKind.HABIT, "gym", "gym", 5, null)
    private fun rows(focus: List<Focus> = listOf(focus47)) = StatsRows(
        versions,
        WeekInput(
            listOf(StatHabit(gym, "gym", 0)),
            mapOf("gym" to listOf(16, 17, 18).map { HabitLog(LocalDate.of(2026, 11, it), 1) }),
            listOf(StatTask("t1", "pay rent", LocalDate.of(2026, 11, 25), null, null)),
            emptySet(),
        ),
        moods = mapOf(LocalDate.of(2026, 11, 16) to 4), journalDays = emptySet(), focus = focus,
    )
    private val sun = LocalDate.of(2026, 11, 22)
    private val mon = LocalDate.of(2026, 11, 23)
    private val wed = LocalDate.of(2026, 11, 18)

    @Test fun reviewOnSundayIsThisWeekAndEditable() {
        val r = buildReview(rows(), sun, weekStart = null)
        assertEquals(w47, r.review.start)
        assertTrue(r.isLastDay)
        assertEquals(listOf("gym 4×  →  3/4 so far"), r.lastWeekSaid)
        assertEquals(w48, r.nextWeek)
        assertTrue(r.canEdit)
        assertEquals("pick 1–2", r.nextNote)
    }

    @Test fun reviewOnMondayIsLastWeekAndEditsThisWeek() {
        val r = buildReview(rows(listOf(focus47, focus48)), mon, weekStart = null)
        assertEquals(w47, r.review.start)
        assertEquals(listOf("gym 4×  →  3/4 · missed by 1"), r.lastWeekSaid)
        assertEquals(0, r.kept)
        assertEquals(w48, r.nextWeek)
        assertEquals(listOf(focus48), r.next)
        assertTrue(r.canEdit)
    }

    @Test fun reviewMidweekIsReadOnly() {
        val r = buildReview(rows(), wed, weekStart = null)
        assertEquals(w47, r.review.start)
        assertFalse(r.canEdit)
        assertEquals("opens sun", r.nextNote)
    }

    @Test fun pagingBackIsReadOnly() {
        val r = buildReview(rows(), sun, weekStart = w47.minusDays(7))
        assertEquals(46, r.review.number)
        assertFalse(r.canEdit)
        assertEquals("closed", r.nextNote)
        assertTrue(r.canGoForward)
        assertFalse(buildReview(rows(), sun, weekStart = null).canGoForward) // nothing after the current week
    }

    @Test fun pickerListsHabitsAndOpenTasksWithGoals() {
        val p = buildReview(rows(), sun, weekStart = null).picker
        assertEquals(listOf(PickItem(FocusKind.HABIT, "gym", "gym", goal = 7, deadline = null, note = "7 due")), p.filter { it.kind == FocusKind.HABIT })
        assertEquals(listOf(PickItem(FocusKind.TASK, "t1", "pay rent", goal = null, deadline = LocalDate.of(2026, 11, 25), note = "due wed")), p.filter { it.kind == FocusKind.TASK })
    }

    @Test fun sundayIsTheOnlyDayThatSilencesThePing() {
        assertEquals(sun, sundayToSilence(sun))
        assertNull(sundayToSilence(mon))
    }

    @Test fun pixelsModeSwitch() {
        val s = buildStats(rows(), wed, year = 2026, mode = PixelMode.MOOD, last30 = true)
        assertEquals(4, s.pixels[10][15])
        assertEquals(47, s.week.number)
        val p = buildStats(rows(), wed, year = 2026, mode = PixelMode.PERFECT, last30 = true)
        assertEquals(2, p.pixels[10][15]) // gym done on 16 nov: a perfect day
    }

    @Test fun weekRangeAcrossMonths() {
        assertEquals("16–22 nov", weekRange(w47))
        assertEquals("30 nov–6 dec", weekRange(LocalDate.of(2026, 11, 30)))
    }
}
