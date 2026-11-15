package app.loopdo.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class LimitsTest {
    @Test
    fun limitsMatchSpec() {
        assertEquals(8, Limits.DAY_END_MAX_HOUR)
        assertEquals(4, Limits.DEFAULT_DAY_END_HOUR)
        assertEquals(7, Limits.FREEZE_EARN_RUN)
        assertEquals(2, Limits.FREEZE_CAP)
        assertEquals(120L, Limits.TAMPER_TOLERANCE_SECONDS)
        assertEquals(4, Limits.MAX_PHOTOS_PER_ENTRY)
        assertEquals(1..5, Limits.MOOD_RANGE)
    }
}
