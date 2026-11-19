package app.loopdo.android.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owner, 2026-11-19: ask once for "Unrestricted" battery, like other apps; never stack it on the notifications ask. */
class BatteryAskTest {
    @Test
    fun `asks once when the app is still optimised`() = assertTrue(shouldAskBattery(unrestricted = false, askedBefore = false, otherAskShowing = false))

    @Test
    fun `never asks twice`() = assertFalse(shouldAskBattery(unrestricted = false, askedBefore = true, otherAskShowing = false))

    @Test
    fun `nothing to ask when already unrestricted`() = assertFalse(shouldAskBattery(unrestricted = true, askedBefore = false, otherAskShowing = false))

    @Test
    fun `waits for the next open while the notifications ask is on screen`() =
        assertFalse(shouldAskBattery(unrestricted = false, askedBefore = false, otherAskShowing = true))

    @Test
    fun `the settings row says where it stands`() {
        assertEquals("unrestricted ✓", batteryLine(unrestricted = true))
        assertEquals("optimised", batteryLine(unrestricted = false))
    }
}
