package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import app.loopdo.desktop.notify.DbusEvent
import app.loopdo.desktop.notify.DesktopPings
import app.loopdo.shared.day.settingsAt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D6 (TEST USER): a task due in two minutes pings on the laptop at its time; [done] on the notification completes it. */
class PingsLiveTest {
    @Test
    fun `a task reminder fires and its button works`() = runBlocking {
        LiveTestUser.assumeLive()
        val g = DesktopGraph(Files.createTempDirectory("loopdo-ping").toFile())
        if (g.auth.session.first { it !is AuthState.Loading } !is AuthState.SignedIn) g.auth.signIn(LiveTestUser.email, LiveTestUser.password)
        withTimeout(20_000) { g.auth.session.first { it is AuthState.SignedIn } }
        withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        withTimeout(30_000) { while (g.anchorStore.current() == null) delay(300) }
        val calls = CopyOnWriteArrayList<List<String>>()
        var next = 100
        val pings = DesktopPings(g, {}, {}, run = { a -> calls += a; "(uint32 ${next++},)" }, watchDbus = false)
        val now = g.trustedClock.now().instant
        val zone = settingsAt(g.todayData.versionsNow(), now).zone
        val at = now.atZone(zone).plusMinutes(2).truncatedTo(ChronoUnit.MINUTES)
        val title = "laptop ping ${UUID.randomUUID().toString().take(5)}"
        val task = g.actions.addTask(title, at.toLocalDate(), at.toLocalTime(), null)
        pings.start()
        val notify = withTimeout(240_000) {
            while (calls.none { c -> c.any { it.contains(title) } }) delay(1_000)
            calls.first { c -> c.any { it.contains(title) } }
        }
        val args = notify.drop(notify.indexOf("--method") + 2)
        val doneAction = Regex("""'([^']*\|done\|\d+)'""").find(args[5])?.groupValues?.get(1)
        assertTrue(doneAction != null, "a [done] button: ${args[5]}")
        val kdeId = next - 1
        withTimeout(30_000) { pings.handle(DbusEvent.Action(kdeId, doneAction!!)) } // it once deadlocked on the scheduler's lock
        withTimeout(20_000) { while (g.sync.db.get("SELECT coalesce(completed_on, '') FROM tasks WHERE id = ?", listOf(task.toString())) { it.getString(0)!! } == "") delay(300) }
        assertTrue(calls.any { it.contains("org.freedesktop.Notifications.CloseNotification") }, "the done reminder goes away")
        withTimeout(60_000) { while (g.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) delay(400) }
        assertEquals(1L, g.sync.db.get("SELECT count(*) FROM tasks WHERE id = ? AND completed_on IS NOT NULL", listOf(task.toString())) { it.getLong(0)!! })
        g.actions.run { runCatching { g.taskActions.deleteTask(task) } }
        g.sync.db.disconnect(); g.scope.cancel()
    }
}
