package app.loopdo.client.journal


import app.loopdo.client.popup.PopupDrafts
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** One draft store for the app's writer and the widget pop-up (review of M6: typed text is never lost). */
class LineDraftsTest {
    private class Memory : PopupDrafts {
        val map = mutableMapOf<String, String>()
        override fun load(key: String) = map[key].orEmpty()
        override fun keys(): Set<String> = map.keys
        override fun save(key: String, text: String) { if (text.isEmpty()) map.remove(key) else map[key] = text }
    }
    private val fri = LocalDate.of(2026, 11, 13)
    private val friNight = Instant.parse("2026-11-13T18:20:00Z") // 23:50 IST
    private val satMorning = Instant.parse("2026-11-14T04:00:00Z")

    @Test
    fun editingAnEarlierDayDraftKeepsItsTime() {
        val d = LineDrafts(Memory())
        d.keep(fri, "long day", friNight, earlierDay = false)
        d.keep(fri, "long day.", satMorning, earlierDay = true) // a typo fixed the next morning
        assertEquals("long day." to friNight, d.line(fri))
    }

    @Test
    fun sharedBetweenTheAppAndThePopup() {
        val store = Memory()
        LineDrafts(store).keep(fri, "started in the app", friNight, earlierDay = false)
        assertEquals("started in the app" to friNight, LineDrafts(store).line(fri)) // the pop-up sees it
        assertEquals(setOf(fri), LineDrafts(store).dates())
        LineDrafts(store).clear(fri)
        assertEquals("" to null, LineDrafts(store).line(fri))
        assertEquals(emptySet<LocalDate>(), LineDrafts(store).dates())
    }

    // M6 minor: a pre-M6 whole-entry draft that was edited in the middle keeps only its new lines (the saved entry
    // isn't added again as new lines).
    @Test
    fun anEditedPreM6DraftKeepsOnlyItsNewLines() {
        val store = Memory()
        store.save("journal:2026-11-13", "walk, then gym\ncalled mom")
        store.save("journal:2026-11-13:base", "walk\ncalled mom")
        assertEquals("walk, then gym", LineDrafts(store).line(java.time.LocalDate.of(2026, 11, 13)).first)
    }
}
