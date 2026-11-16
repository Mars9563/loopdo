package app.loopdo.android.widget

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetRefreshTest {
    private fun t(s: String) = Instant.parse(s)

    @Test
    fun alarmsAtDayEndAndEvery15Minutes() {
        // Day end (+1 s, so the redraw lands on the new day) and the countdown's 15-minute refresh.
        assertEquals(listOf(t("2026-11-14T22:30:01Z"), t("2026-11-14T22:35:00Z")), widgetAlarms(t("2026-11-14T22:20:00Z"), t("2026-11-14T22:30:00Z")))
        // Sorted when day end is the later one.
        assertEquals(listOf(t("2026-11-14T20:15:00Z"), t("2026-11-14T22:30:01Z")), widgetAlarms(t("2026-11-14T20:00:00Z"), t("2026-11-14T22:30:00Z")))
        // Day end already passed: only the 15-minute refresh.
        assertEquals(listOf(t("2026-11-14T22:45:00Z")), widgetAlarms(t("2026-11-14T22:30:00Z"), t("2026-11-14T22:29:59Z")))
    }

    /**
     * Alarms run on the boot clock (elapsed realtime), measured from trusted time, so a wrong or
     * hand-set wall clock can neither shift them nor make them re-arm in the past (final review I1).
     */
    @Test
    fun alarmsAreDelaysOnTheBootClock() {
        val trustedNow = t("2026-11-14T22:20:00Z")
        assertEquals(listOf(1_000_000L + 601_000, 1_000_000L + 900_000), elapsedAlarms(trustedNow, 1_000_000L, t("2026-11-14T22:30:00Z")))
        // Day end already passed by trusted time: only the refresh, never a target in the past.
        assertEquals(listOf(5_000L + 900_000), elapsedAlarms(trustedNow, 5_000L, t("2026-11-14T22:19:00Z")))
    }
}
