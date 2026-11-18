package app.loopdo.client.tasks


import app.loopdo.shared.task.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** The task screens' rules (locked habits-tasks-v1 screens 5–8). Today = mon 16 nov 2026. */
class TasksViewModelTest {
    private val today = LocalDate.of(2026, 11, 16)

    @Test
    fun repeatChipCyclesInOrder() {
        val seen = mutableListOf<RepeatRule?>()
        var r: RepeatRule? = null
        repeat(5) { r = repeatChipCycle(r, today); seen += r }
        assertEquals(
            listOf(RepeatRule.Daily, RepeatRule.Weekly(setOf(DayOfWeek.MONDAY)), RepeatRule.MonthlyOnDay(16), RepeatRule.EveryNDays(2, today), null),
            seen,
        )
        assertEquals("↻ repeat", repeatChipLabel(null))
        assertEquals(listOf("↻ daily", "↻ weekly", "↻ monthly", "↻ every 2 days"), seen.filterNotNull().map(::repeatChipLabel))
    }

    @Test
    fun nextDatesForMonthlyDay31() {
        assertEquals(
            listOf(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31)),
            nextDates(RepeatRule.MonthlyOnDay(31), today),
        )
        // A series starting later: its first dates; weekly on wed + sat from mon.
        assertEquals(listOf(LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1)), nextDates(RepeatRule.MonthlyOnDay(1), LocalDate.of(2026, 12, 1)))
        assertEquals(
            listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 25)),
            nextDates(RepeatRule.Weekly(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY)), today, 3),
        )
        assertEquals("next: tue 1 dec, fri 1 jan", nextLine(RepeatRule.MonthlyOnDay(1), LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun taskEditorAsksThisOrFuture() {
        assertEquals(SaveScope.ONE, saveScope(isTemplate = false, isInstance = false))
        assertEquals(SaveScope.ASK, saveScope(isTemplate = false, isInstance = true)) // one occurrence of a series
        assertEquals(SaveScope.SERIES, saveScope(isTemplate = true, isInstance = false)) // from the REPEATING section
    }

    @Test
    fun repeatingRowShowsTheNextDate() {
        val rows = repeatingRows(
            listOf(
                TemplateRow("t1", "pay rent", RepeatRule.MonthlyOnDay(1), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1)),
                TemplateRow("t2", "water plants", RepeatRule.Daily, LocalDate.of(2026, 11, 10), today),
                TemplateRow("t3", "old", RepeatRule.Daily, LocalDate.of(2026, 1, 1), STOPPED),
            ),
            today,
        )
        assertEquals(listOf("pay rent · next tue 1 dec", "water plants · next tue 17 nov"), rows.map { it.line })
    }
}
