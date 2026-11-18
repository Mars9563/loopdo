package app.loopdo.android.popup

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class JournalDraftDayTest {
    @Test
    fun unsavedTextFromAnEarlierDayReopensThere() {
        val mon = LocalDate.of(2026, 11, 16)
        assertEquals(mon.minusDays(2), journalDateToOpen(mon, setOf(mon.minusDays(5), mon.minusDays(2))))
        assertEquals(mon, journalDateToOpen(mon, setOf(mon)))
        assertEquals(mon, journalDateToOpen(mon, emptySet()))
    }
}
