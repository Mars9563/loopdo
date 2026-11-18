package app.loopdo.android.journal

import app.loopdo.client.journal.preview

import app.loopdo.client.journal.EntryRow
import app.loopdo.client.journal.LineRow
import app.loopdo.client.journal.LogItem
import app.loopdo.client.journal.Lru
import app.loopdo.client.journal.PhotoRow
import app.loopdo.client.journal.buildJournal
import app.loopdo.client.journal.canAddPhoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The journal tab's model (locked journal-log-v2): [today], [feed] with ON THIS DAY, the alt day bar. Today = sat 14 nov 2026. */
class JournalViewModelTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 14)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private val entries = listOf(
        EntryRow("e14", today, "[11:00] so i heard something funny today.\n[13:00] had this for lunch", 4),
        EntryRow("e13", d(2026, 11, 13), "[19:05] long day at college.", 3),
        EntryRow("e12", d(2026, 11, 12), "", null), // only a photo
        EntryRow("old", d(2025, 11, 14), "[18:20] first snow on the way back. bought the A55 today.", 5),
        EntryRow("empty", d(2026, 11, 10), "  ", null), // nothing in it: not a journal day
    )
    private val photos = listOf(
        PhotoRow("p1", "e14", Instant.parse("2026-11-14T05:30:00Z"), "/files/photos/p1.webp"), // 11:00 IST
        PhotoRow("p2", "e12", null, null),
    )
    private val model = buildJournal(entries, photos, today, ist, LocalTime.of(4, 0), streak = 34)

    @Test
    fun feedIsNewestFirstWithOnThisDay() {
        assertEquals(listOf(today, d(2026, 11, 13), d(2026, 11, 12), d(2025, 11, 14)), model.feed.map { it.date }) // every day, newest first
        assertEquals(listOf(d(2025, 11, 14)), model.onThisDay.map { it.date })
        assertEquals(34, model.streak)
    }

    @Test
    fun todayViewShowsOnlyToday() {
        val t = model.today
        assertEquals(today, t.date)
        val items = t.blocks.flatMap { it.items }
        assertEquals(listOf("so i heard something funny today.", "photo:p1", "had this for lunch"), items.map {
            when (it) { is LogItem.Line -> it.line.text; is LogItem.Photo -> "photo:${it.id}" }
        })
        assertEquals("/files/photos/p1.webp", t.localPaths["p1"])
        // A day with nothing written yet still has its bar.
        val blank = buildJournal(emptyList(), emptyList(), today, ist, LocalTime.of(4, 0), streak = 0).today
        assertTrue(blank.blocks.isEmpty())
        assertNull(blank.mood)
    }

    @Test
    fun dayBarShowsMoodAndToday() {
        assertEquals(listOf("sat 14 nov", "🙂", "today"), model.feed[0].let { listOf(it.label, it.moodFace, it.dayTag) })
        assertEquals(listOf("fri 13 nov", "😐", ""), model.feed[1].let { listOf(it.label, it.moodFace, it.dayTag) })
        assertEquals("", model.feed[2].moodFace)
    }

    // Review of M6, important 2: a full day doesn't offer [📷 photo] (a camera shot can't be taken and then refused).
    @Test
    fun fifthPhotoIsNotOfferedOnAFullDay() {
        val four = (1..4).map { PhotoRow("p$it", "e14", null, null) }
        assertTrue(canAddPhoto(buildJournal(entries, photos, today, ist, LocalTime.of(4, 0), 0).today))
        assertEquals(false, canAddPhoto(buildJournal(entries, four, today, ist, LocalTime.of(4, 0), 0).today))
    }

    // Review of M6, important 3: decoded photos are kept in a small LRU, not all at once.
    @Test
    fun lruKeepsTheMostRecent() {
        val lru = Lru<String, Int>(2)
        lru["a"] = 1; lru["b"] = 2
        lru["a"] // touch a
        lru["c"] = 3 // evicts b, the least recently used
        assertEquals(listOf(1, null, 3), listOf(lru["a"], lru["b"], lru["c"]))
    }

    // M6 minor: a day with only a mood (no line or photo) isn't a journal day, but its mood shows in the calendar.
    @Test
    fun aMoodOnlyDayKeepsItsMoodForTheCalendar() {
        val m = buildJournal(entries + EntryRow("mood", d(2026, 11, 11), "", 2), photos, today, ist, LocalTime.of(4, 0), streak = 0)
        assertTrue(m.feed.none { it.date == d(2026, 11, 11) })
        assertEquals(2, m.moods[d(2026, 11, 11)])
        assertEquals(4, m.moods[today])
    }

    // M6 minor: ON THIS DAY previews the day's first 2 items (lines or photos), not its first 2 blocks.
    @Test
    fun onThisDayPreviewsTwoLines() {
        val day = buildJournal(
            listOf(EntryRow("old", d(2025, 11, 14), "[08:00] a\n[08:05] b\n[08:10] c\n[12:00] d", null)), emptyList(), today, ist, LocalTime.of(4, 0), 0,
        ).onThisDay.single()
        val items = day.preview().blocks.flatMap { it.items }.filterIsInstance<LogItem.Line>().map { it.line.text }
        assertEquals(listOf("a", "b"), items)
    }

    // M6 minor: a past day's times show in the zone that day was in (a trip abroad doesn't shift old stamps).
    @Test
    fun aPastDayShowsItsOwnZone() {
        val london = ZoneId.of("Europe/London")
        val m = buildJournal(
            listOf(EntryRow("e13", d(2026, 11, 13), "", null)), emptyList(), today, london, LocalTime.of(4, 0), 0,
            lines = listOf(LineRow("l", "e13", Instant.parse("2026-11-13T13:30:00Z"), "lunch", 0)), // 19:00 IST
            zoneOn = { day -> if (day < today) ist else london },
        )
        val line = m.feed.single().blocks.flatMap { it.items }.filterIsInstance<LogItem.Line>().single()
        assertEquals(LocalTime.of(19, 0), line.line.time)
    }
}
