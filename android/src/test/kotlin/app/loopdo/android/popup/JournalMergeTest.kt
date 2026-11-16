package app.loopdo.android.popup

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Owner, 2026-11-16: a journal draft is merged with newer text, never allowed to overwrite it. */
class JournalMergeTest {
    @Test
    fun unchangedEntryTakesTheDraft() = assertEquals("walk and gym", mergeJournal(current = "walk", base = "walk", draft = "walk and gym"))

    @Test
    fun anAddedLineSurvivesTheDraftsAddition() {
        // Draft started from "walk"; meanwhile [+ add] appended "called mom".
        assertEquals("walk\ncalled mom\nand gym", mergeJournal(current = "walk\ncalled mom", base = "walk", draft = "walk and gym"))
    }

    @Test
    fun aDraftThatChangedNothingKeepsTheNewerText() =
        assertEquals("walk\ncalled mom", mergeJournal(current = "walk\ncalled mom", base = "walk", draft = "walk"))

    @Test
    fun anEditThatCantBeReplayedIsKeptBelowAMarker() {
        assertEquals(
            "walk\ncalled mom\n\n[unsaved draft]\nWalked 5k",
            mergeJournal(current = "walk\ncalled mom", base = "walk", draft = "Walked 5k"),
        )
    }

    @Test
    fun unsavedTextFromAnEarlierDayReopensThere() {
        val mon = LocalDate.of(2026, 11, 16)
        assertEquals(mon.minusDays(2), journalDateToOpen(mon, setOf(mon.minusDays(5), mon.minusDays(2))))
        assertEquals(mon, journalDateToOpen(mon, setOf(mon)))
        assertEquals(mon, journalDateToOpen(mon, emptySet()))
    }
}
