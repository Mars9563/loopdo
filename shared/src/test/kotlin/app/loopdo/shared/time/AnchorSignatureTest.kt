package app.loopdo.shared.time

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnchorSignatureTest {
    private val secret = "test-secret-at-least-32-bytes-long!!".toByteArray()
    private val t = Instant.parse("2026-11-14T12:30:00Z")

    @Test
    fun anchorRoundTrip() = assertTrue(verifyAnchor(secret, "u1", t, signAnchor(secret, "u1", t)))

    @Test
    fun anchorRejectsOtherUser() = assertFalse(verifyAnchor(secret, "u2", t, signAnchor(secret, "u1", t)))

    @Test
    fun anchorRejectsChangedTime() =
        assertFalse(verifyAnchor(secret, "u1", t.minusSeconds(86_400), signAnchor(secret, "u1", t)))

    @Test
    fun anchorRejectsOtherSecret() =
        assertFalse(verifyAnchor("another-secret-also-32-bytes-long!".toByteArray(), "u1", t, signAnchor(secret, "u1", t)))

    @Test
    fun garbageSignatureIsFalseNotException() = assertFalse(verifyAnchor(secret, "u1", t, "not*base64"))
}
