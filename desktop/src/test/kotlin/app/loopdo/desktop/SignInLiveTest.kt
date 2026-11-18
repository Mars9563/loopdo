package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D2 Task 3: the laptop signs in as the test user, syncs, and holds the user's profile row. */
class SignInLiveTest {
    @Test
    fun `signs in, syncs down and keeps the session`() = runBlocking {
        LiveTestUser.assumeLive()
        val dir = Files.createTempDirectory("loopdo-live").toFile()
        val g = DesktopGraph(dir)
        assertTrue(g.auth.signIn(LiveTestUser.email, LiveTestUser.password).isSuccess, "sign-in")
        val s = withTimeout(20_000) { g.auth.session.first { it is AuthState.SignedIn } } as AuthState.SignedIn
        assertEquals(LiveTestUser.userId, s.userId.toString())
        withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        assertEquals(1L, g.sync.db.get("SELECT count(*) FROM profiles WHERE id = ?", listOf(LiveTestUser.userId)) { it.getLong(0)!! })
        assertTrue(java.io.File(dir, "session.json").exists(), "session kept in the data folder")
        g.sync.db.disconnect(); g.scope.cancel()

        // A second start with the same folder is still signed in (no password asked again).
        val again = DesktopGraph(dir)
        val s2 = withTimeout(20_000) { again.auth.session.first { it !is AuthState.Loading } }
        assertTrue(s2 is AuthState.SignedIn, "still signed in after restart, got $s2")
        again.sync.db.disconnect(); again.scope.cancel()
    }
}
