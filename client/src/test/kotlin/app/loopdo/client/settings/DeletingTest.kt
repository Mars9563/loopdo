package app.loopdo.client.settings


import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The 7 days after "delete in 7 days" (locked settings-v1 D, bottom). */
class DeletingTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val asked = Instant.parse("2026-11-18T11:30:00Z") // wed 18 nov 17:00 IST

    @Test fun deleteOnIsSevenDaysLater() {
        val d = deletingState(asked, null, ist)!!
        assertEquals(LocalDate.of(2026, 11, 25), d.deleteOn)
        assertEquals("wed 25 nov", d.deleteOnLabel)
        assertEquals(true, d.canKeep)
    }

    @Test fun claimedCannotKeep() {
        assertEquals(false, deletingState(asked, asked.plusSeconds(8 * 86_400L), ist)!!.canKeep)
    }

    @Test fun noRequestNoScreen() {
        assertNull(deletingState(null, null, ist))
    }
}
