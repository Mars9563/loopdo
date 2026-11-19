package app.loopdo.client.notify

import kotlin.test.Test
import kotlin.test.assertEquals

/** D7: while a laptop is in use the phone's ping goes to the quiet channel, buttons and text unchanged. */
class LaptopRouteTest {
    private val buttons = listOf(Button("[done]", "done"), Button("[snooze 30m]", "snooze"))

    @Test
    fun `in use, every ringing channel goes quiet with its buttons`() {
        listOf(CH_HABITS, CH_TASKS, CH_JOURNAL, CH_DAY_END, CH_STATS).forEach { ch ->
            val s = Shown(ch, "pay rent · now", "body", buttons)
            assertEquals(s.copy(channel = CH_LAPTOP), routeForLaptop(s, laptopInUse = true), ch)
        }
    }

    @Test
    fun `not in use, nothing changes`() {
        val s = Shown(CH_TASKS, "pay rent · now", "", buttons)
        assertEquals(s, routeForLaptop(s, laptopInUse = false))
    }

    @Test
    fun `the night warning stays on its own silent channel`() {
        val s = Shown(CH_DAY_END_NIGHT, "the day ends in 1 hour", "", emptyList())
        assertEquals(s, routeForLaptop(s, laptopInUse = true))
    }
}
