package app.loopdo.shared.remind

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D7: the phone rings unless a laptop says it is in use right now. */
class PresenceTest {
    private val now = Instant.parse("2026-11-19T07:00:00Z")

    @Test
    fun `nothing active rings`() = assertFalse(laptopInUse(emptyList(), now))

    @Test
    fun `a row five minutes old rings again`() = assertFalse(laptopInUse(listOf(now.minusSeconds(300)), now))

    @Test
    fun `active until exactly now is over`() = assertFalse(laptopInUse(listOf(now), now))

    @Test
    fun `one active laptop of two is enough`() = assertTrue(laptopInUse(listOf(now.minusSeconds(60), now.plusSeconds(240)), now))

    @Test
    fun `presenceId is the server's id`() = assertEquals(
        presenceId(UUID.fromString("00000000-0000-0000-0000-00000000000a"), UUID.fromString("0d000000-0000-4000-8000-0000000000d7")),
        app.loopdo.shared.task.uuidV5(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "presence:0d000000-0000-4000-8000-0000000000d7"),
    )
}
