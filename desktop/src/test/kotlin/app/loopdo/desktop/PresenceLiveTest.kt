package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import app.loopdo.client.presence.PresenceActions
import app.loopdo.shared.remind.presenceId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D7 (TEST USER): a laptop's presence uploads, comes back from the server clamped, and is moved back to now. */
class PresenceLiveTest {
    @Test
    fun `presence round-trips through the server`() = runBlocking {
        LiveTestUser.assumeLive()
        val g = DesktopGraph(Files.createTempDirectory("loopdo-presence").toFile())
        if (g.auth.session.first { it !is AuthState.Loading } !is AuthState.SignedIn) g.auth.signIn(LiveTestUser.email, LiveTestUser.password)
        val user = withTimeout(20_000) { (g.auth.session.first { it is AuthState.SignedIn } as AuthState.SignedIn).userId }
        withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        withTimeout(30_000) { while (g.anchorStore.current() == null) delay(300) }
        val device = UUID.randomUUID()
        val p = PresenceActions(g.sync.db, { user }, device)
        val now = g.trustedClock.now().instant
        suspend fun idle() = withTimeout(60_000) { while (g.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) delay(300) }
        suspend fun stored() = g.sync.db.get("SELECT active_until FROM device_presence WHERE id = ?", listOf(presenceId(user, device).toString())) { it.getString(0)!! }

        p.setActiveUntil(now.plus(Duration.ofHours(2))) // a laptop clock running fast
        idle()
        assertTrue(g.sync.notices.pending.value.isEmpty(), "the server took it: ${g.sync.notices.pending.value}")
        // The server's clamped value syncs back down: never more than 10 min ahead.
        withTimeout(30_000) {
            while (java.time.OffsetDateTime.parse(stored()).toInstant() > now.plus(Duration.ofMinutes(11))) delay(500)
        }
        p.setActiveUntil(now) // stopped being in use
        idle()
        assertTrue(g.sync.notices.pending.value.isEmpty(), "the PATCH was taken too")
        withTimeout(30_000) { while (java.time.OffsetDateTime.parse(stored()).toInstant() != now) delay(500) }
        assertEquals(1L, g.sync.db.get("SELECT count(*) FROM device_presence WHERE id = ?", listOf(presenceId(user, device).toString())) { it.getLong(0)!! })
        g.sync.db.disconnect(); g.scope.cancel()
    }
}
