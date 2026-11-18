package app.loopdo.desktop

import app.loopdo.client.popup.journalDateToOpen
import app.loopdo.desktop.quick.QuickItem
import app.loopdo.desktop.quick.parseQuick
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickTest {
    @Test
    fun `t prefix makes a task, anything else a journal line`() {
        assertEquals(QuickItem.Task("buy milk"), parseQuick("t buy milk"))
        assertEquals(QuickItem.Task("call home"), parseQuick("T: call home"))
        assertEquals(QuickItem.Journal("today was ok"), parseQuick("today was ok"))
        assertEquals(QuickItem.Journal("tea at 5"), parseQuick("  tea at 5 "))
        assertNull(parseQuick("t"))
        assertNull(parseQuick("t:  "))
        assertNull(parseQuick("   "))
    }

    @Test
    fun `a draft kept before the day end opens on its own day (the phone pop-up's rule)`() {
        val d = LocalDate.of(2026, 11, 18)
        assertEquals(d, journalDateToOpen(d.plusDays(1), setOf(d)))
        assertEquals(d.plusDays(1), journalDateToOpen(d.plusDays(1), emptySet()))
    }
}
