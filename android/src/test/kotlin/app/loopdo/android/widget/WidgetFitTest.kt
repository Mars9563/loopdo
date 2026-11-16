package app.loopdo.android.widget

import kotlin.test.Test
import kotlin.test.assertEquals

/** The widget has no scrolling list (owner, 2026-11-15): rows that don't fit become "+n more". */
class WidgetFitTest {
    @Test
    fun everythingFits() = assertEquals(Fit(habits = 5, tasks = 4, more = 0), fitRows(habits = 5, tasks = 4, slots = 9))

    @Test
    fun overflowKeepsOneSlotForMoreAndFillsInListOrder() {
        // 12 slots, 5 habits + 10 tasks: 11 rows shown in list order, the last slot says "+4 more".
        assertEquals(Fit(habits = 5, tasks = 6, more = 4), fitRows(habits = 5, tasks = 10, slots = 12))
        // Even the habits don't fit.
        assertEquals(Fit(habits = 3, tasks = 0, more = 6), fitRows(habits = 7, tasks = 2, slots = 4))
    }

    @Test
    fun tinyWidgetStillSaysWhatIsHidden() {
        assertEquals(Fit(habits = 0, tasks = 0, more = 3), fitRows(habits = 2, tasks = 1, slots = 1))
        assertEquals(Fit(habits = 0, tasks = 0, more = 3), fitRows(habits = 2, tasks = 1, slots = 0))
    }

    @Test
    fun slotsFromTheWidgetHeight() {
        // 660 dp tall at font scale 1: fixed parts ~180 dp, rows 48 dp (owner, 2026-11-16) → 10 slots.
        assertEquals(10, rowSlots(heightDp = 660f, fontScale = 1f))
        // Bigger text takes more room for the header lines.
        assertEquals(9, rowSlots(heightDp = 660f, fontScale = 1.3f))
        assertEquals(0, rowSlots(heightDp = 150f, fontScale = 1f))
    }
}
