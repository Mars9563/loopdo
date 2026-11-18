package app.loopdo.android

import app.loopdo.android.ui.nav.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkTest {
    @Test fun writeLinkOpensJournalWriter() {
        assertEquals(Link(Tab.JOURNAL, null, null, write = true, nonce = 0), linkFrom("journal", null, null, true)!!.copy(nonce = 0))
    }

    @Test fun plainJournalLinkDoesNotWrite() {
        assertEquals(Link(Tab.JOURNAL, null, null, write = false, nonce = 0), linkFrom("journal", null, null, false)!!.copy(nonce = 0))
    }

    @Test fun writeOnlyCountsOnTheJournal() {
        assertEquals(false, linkFrom("today", null, null, true)!!.write)
    }

    @Test fun habitAndTaskLinksStillWork() {
        assertEquals(Tab.HABITS, linkFrom(null, "h1", null, false)!!.tab)
        assertEquals(Tab.TODAY, linkFrom(null, null, "t1", false)!!.tab)
        assertNull(linkFrom(null, null, null, false))
    }
}
