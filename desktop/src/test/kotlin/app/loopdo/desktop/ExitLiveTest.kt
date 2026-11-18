package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D2 exit checks with the TEST USER: two devices, offline + restart, and a wiped data folder. */
class ExitLiveTest {
    private suspend fun start(dir: File): DesktopGraph {
        val g = DesktopGraph(dir)
        if (g.auth.session.first { it !is AuthState.Loading } !is AuthState.SignedIn) {
            assertTrue(g.auth.signIn(LiveTestUser.email, LiveTestUser.password).isSuccess)
        }
        withTimeout(20_000) { g.auth.session.first { it is AuthState.SignedIn } }
        withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        return g
    }
    private suspend fun DesktopGraph.stop() { sync.db.disconnect(); sync.db.close(); scope.cancel() }
    private suspend fun DesktopGraph.queue() = sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! }
    private suspend fun DesktopGraph.taskCount(title: String) = sync.db.get("SELECT count(*) FROM tasks WHERE title = ?", listOf(title)) { it.getLong(0)!! }

    @Test
    fun `a change on one device shows on the other within seconds`() = runBlocking {
        LiveTestUser.assumeLive()
        val a = start(Files.createTempDirectory("loopdo-a").toFile())
        val b = start(Files.createTempDirectory("loopdo-b").toFile())
        val title = "from a ${UUID.randomUUID().toString().take(6)}"
        a.actions.addTask(title, null, null, null)
        val t0 = System.currentTimeMillis()
        withTimeout(30_000) { while (b.taskCount(title) == 0L) delay(250) }
        println("device b saw it after ${System.currentTimeMillis() - t0} ms")
        a.stop(); b.stop()
    }

    @Test
    fun `offline changes survive a restart and upload when back online`() = runBlocking {
        LiveTestUser.assumeLive()
        val dir = Files.createTempDirectory("loopdo-offline").toFile()
        val g = start(dir)
        g.sync.goOffline()
        val title = "offline ${UUID.randomUUID().toString().take(6)}"
        g.actions.addTask(title, null, null, null)
        assertTrue(g.queue() > 0, "queued while offline")
        g.stop()
        // Restart with the same folder (still signed in): the queued change is still there and goes up.
        val again = DesktopGraph(dir)
        withTimeout(20_000) { again.auth.session.first { it is AuthState.SignedIn } }
        withTimeout(90_000) { while (again.queue() > 0) delay(500) }
        val witness = start(Files.createTempDirectory("loopdo-witness").toFile())
        withTimeout(30_000) { while (witness.taskCount(title) == 0L) delay(250) }
        again.stop(); witness.stop()
    }

    @Test
    fun `a wiped data folder restores everything on sign-in`() = runBlocking {
        LiveTestUser.assumeLive()
        val first = start(Files.createTempDirectory("loopdo-w1").toFile())
        val counts = listOf("habits", "habit_logs", "tasks", "day_settings_versions").map { t -> first.sync.db.get("SELECT count(*) FROM $t") { it.getLong(0)!! } }
        first.stop()
        val fresh = start(Files.createTempDirectory("loopdo-w2").toFile())
        val again = listOf("habits", "habit_logs", "tasks", "day_settings_versions").map { t -> fresh.sync.db.get("SELECT count(*) FROM $t") { it.getLong(0)!! } }
        assertEquals(counts, again)
        assertTrue(counts[0] >= 2 && counts[3] >= 1, "test user has habits and day settings: $counts")
        fresh.stop()
    }
}
