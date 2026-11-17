package app.loopdo.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/** The journal as a time log (M6, locked journal-log-v2): stamps, order, 30-minute blocks, line edits. */
class JournalLogTest {
    private fun t(h: Int, m: Int) = LocalTime.of(h, m)
    private fun lines(b: LogBlock) = b.items.map { when (it) { is LogItem.Line -> it.line.text; is LogItem.Photo -> "photo:${it.id}" } }

    @Test
    fun stampFormatsHoursAndMinutes() {
        assertEquals(listOf("[09:05] a", "[09:05] b"), stampLines("a\n\n  b  ", t(9, 5)))
        assertEquals(listOf(LogLine(t(9, 5), "a"), LogLine(null, "old line")), parseLines("[09:05] a\nold line\n\n"))
    }

    @Test
    fun untimedLinesComeFirst() {
        val blocks = dayLog("[11:00] new\nwritten before times\n[11:05] newer", emptyList())
        assertEquals(listOf(listOf("written before times"), listOf("new", "newer")), blocks.map(::lines))
    }

    @Test
    fun photosMergeByTime() {
        val text = "[11:00] so i heard something funny today.\n[13:00] had this for lunch\n[22:40] gym skipped"
        val blocks = dayLog(text, listOf("p1" to t(11, 0), "p2" to t(13, 2), "p3" to null))
        assertEquals(
            listOf(
                listOf("so i heard something funny today.", "photo:p1"),
                listOf("had this for lunch", "photo:p2"),
                listOf("gym skipped", "photo:p3"), // a photo without a time goes last
            ),
            blocks.map(::lines),
        )
    }

    @Test
    fun gapOf30MinutesStartsABlock() {
        assertEquals(1, dayLog("[10:00] a\n[10:29] b", emptyList()).size)
        assertEquals(2, dayLog("[10:00] a\n[10:30] b", emptyList()).size)
    }

    @Test
    fun afterMidnightLineStaysInOrder() {
        // Day end 04:00: a line at 01:30 belongs to the same LoopDo day and stays after 23:50 (save order).
        val blocks = dayLog("[23:50] late night\n[01:30] still up", listOf("p" to t(0, 10)))
        assertEquals(listOf("late night", "photo:p", "still up"), blocks.flatMap(::lines))
    }

    @Test
    fun replaceAndRemoveTouchOneLine() {
        val text = "[13:00] ok\n[13:00] ok\n[14:00] fine"
        assertEquals("[13:00] okay\n[13:00] ok\n[14:00] fine", replaceLine(text, "[13:00] ok", "okay"))
        assertEquals("[13:00] ok\n[14:00] fine", removeLine(text, "[13:00] ok"))
        assertEquals("written before\n[14:00] fine", replaceLine("old\n[14:00] fine", "old", "written before"))
        assertNull(replaceLine(text, "[09:00] gone", "x"))
        assertNull(removeLine(text, "[09:00] gone"))
    }
}
