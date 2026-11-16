package app.loopdo.android.widget

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Every widget tap target names its own item (M4 final review C1): Android identifies a widget
 * PendingIntent by its data, so a row still on screen can only ever fire its own action.
 */
class WidgetTapTest {
    private val sat = LocalDate.of(2026, 11, 14)

    @Test
    fun roundTrips() {
        val tap = WidgetTap(TapKind.TICK, "6f1d6c1a-1111-4c55-9d8e-3f0a1b2c3d4e", sat, done = false)
        assertEquals("loopdo-widget://tap/tick/6f1d6c1a-1111-4c55-9d8e-3f0a1b2c3d4e?date=2026-11-14&done=false", tap.toUri())
        assertEquals(tap, parseWidgetTap(tap.toUri()))
        val task = WidgetTap(TapKind.TASK, "a", sat, done = true)
        assertEquals(task, parseWidgetTap(task.toUri()))
    }

    @Test
    fun differentItemsAndKindsNeverShareAnIdentity() {
        val a = WidgetTap(TapKind.TICK, "a", sat, false).toUri()
        assertNotEquals(a, WidgetTap(TapKind.TICK, "b", sat, false).toUri())
        assertNotEquals(a, WidgetTap(TapKind.PLUS, "a", sat, false).toUri())
        assertNotEquals(a, WidgetTap(TapKind.TICK, "a", sat.plusDays(1), false).toUri())
    }

    @Test
    fun foreignOrBrokenDataIsIgnored() {
        assertNull(parseWidgetTap(null))
        assertNull(parseWidgetTap("https://example.com/tap/tick/a?date=2026-11-14&done=false"))
        assertNull(parseWidgetTap("loopdo-widget://tap/explode/a?date=2026-11-14&done=false"))
        assertNull(parseWidgetTap("loopdo-widget://tap/tick/a?date=not-a-date&done=false"))
    }

    @Test
    fun completeAndStepRoundTrip() {
        val complete = WidgetTap(TapKind.COMPLETE, "k", sat, done = false)
        assertEquals(complete, parseWidgetTap(complete.toUri()))
        val plus5 = WidgetTap(TapKind.PLUS, "k", sat, done = false, step = 5)
        assertEquals(plus5, parseWidgetTap(plus5.toUri()))
        assertNotEquals(plus5.toUri(), WidgetTap(TapKind.PLUS, "k", sat, done = false, step = 1).toUri())
    }
}

