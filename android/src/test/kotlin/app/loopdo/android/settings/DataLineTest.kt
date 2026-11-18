package app.loopdo.android.settings

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Is my data safe?" (M9, locked settings-v1 A/B and C). */
class DataLineTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-18T11:20:00Z") // 16:50 IST
    private fun ago(min: Long) = now.minusSeconds(min * 60)

    @Test fun allSaved() {
        assertEquals("all saved" to "2 min ago", dataLine(Waiting(0, 0, null, ago(2), online = true), now, ist))
        assertEquals("all saved" to "just now", dataLine(Waiting(0, 0, null, now.minusSeconds(20), true), now, ist))
        assertEquals("all saved" to "3 h ago", dataLine(Waiting(0, 0, null, ago(185), true), now, ist))
    }

    @Test fun changesAndPhotosWaiting() {
        val w = Waiting(3, 1, ago(168), Instant.parse("2026-11-18T08:32:00Z"), online = false) // last saved 14:02 IST
        assertEquals("3 changes + 1 photo waiting" to "offline · last saved 14:02\nthey go up when you're online", dataLine(w, now, ist))
        assertEquals("3 changes + 1 photo waiting" to "online · sending…", dataLine(w.copy(online = true), now, ist))
    }

    @Test fun photoWaitingIsNotAllSaved() {
        assertEquals("1 photo waiting", dataLine(Waiting(0, 1, ago(1), ago(5), true), now, ist).first)
    }

    @Test fun singularWording() {
        assertEquals("1 change + 1 photo waiting", dataLine(Waiting(1, 1, ago(1), null, true), now, ist).first)
        assertEquals("2 changes waiting", dataLine(Waiting(2, 0, ago(1), null, true), now, ist).first)
        assertEquals("offline · not saved yet\nthey go up when you're online", dataLine(Waiting(2, 0, ago(1), null, false), now, ist).second)
    }

    @Test fun todayMarkOnlyAfterTenMinutes() {
        assertNull(todayMark(Waiting(3, 0, ago(9), null, false), now))
        assertEquals("↑3", todayMark(Waiting(3, 0, ago(11), null, false), now))
        assertEquals("↑4", todayMark(Waiting(3, 1, ago(30), null, false), now))
        assertNull(todayMark(Waiting(0, 0, null, ago(1), true), now))
    }
}
