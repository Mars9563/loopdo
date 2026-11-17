package app.loopdo.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** The journal [calendar] (locked README v2 screen 3: a month grid coloured by mood; tap a day to read it). */
class CalendarModelTest {
    private val today = LocalDate.of(2026, 11, 18)

    @Test
    fun moodShadeForEachMood() {
        val shades = (1..5).map { moodShade(it) }
        assertTrue("brighter for a better mood", shades.zipWithNext().all { (a, b) -> b > a })
        assertTrue(shades.all { it in 0.15f..0.9f })
        assertEquals(0f, moodShade(null)) // an entry without a mood: outline only
    }

    @Test
    fun monthNavigationStopsAtFirstEntry() {
        val first = LocalDate.of(2026, 10, 3)
        assertTrue(canGoBack(YearMonth.of(2026, 11), first))
        assertFalse(canGoBack(YearMonth.of(2026, 10), first))
        assertFalse(canGoForward(YearMonth.of(2026, 11), today))
        assertTrue(canGoForward(YearMonth.of(2026, 10), today))
        assertFalse(canGoBack(YearMonth.of(2026, 11), null)) // nothing written yet
    }

    @Test
    fun monthCellsMarkEntriesTodayAndFuture() {
        val days = mapOf(LocalDate.of(2026, 11, 15) to 4, LocalDate.of(2026, 11, 16) to null)
        val cells = journalMonth(days, YearMonth.of(2026, 11), today).associateBy { it.date }
        assertEquals(30, cells.size)
        assertEquals(4, cells[LocalDate.of(2026, 11, 15)]!!.mood)
        assertTrue(cells[LocalDate.of(2026, 11, 16)]!!.hasEntry)
        assertFalse(cells[LocalDate.of(2026, 11, 17)]!!.hasEntry)
        assertTrue(cells[today]!!.today)
        assertTrue(cells[LocalDate.of(2026, 11, 19)]!!.future)
    }
}
