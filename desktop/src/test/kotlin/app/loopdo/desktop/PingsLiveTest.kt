package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import app.loopdo.desktop.notify.DbusEvent
import app.loopdo.desktop.notify.DesktopPings
import app.loopdo.desktop.notify.runCommand
import app.loopdo.shared.day.settingsAt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
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
        // With a desktop session the notification is real (KDE shows it and gives its id); else a fake gdbus.
        val realKde = System.getenv("DBUS_SESSION_BUS_ADDRESS") != null && File("/usr/bin/gdbus").exists()
        val calls = CopyOnWriteArrayList<List<String>>()
        val outs = CopyOnWriteArrayList<String>()
        var next = 100
        val pings = DesktopPings(g, {}, {}, run = { a -> calls += a; (if (realKde) runCommand(a) else "(uint32 ${next++},)").also { outs += it } }, watchDbus = false)
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
        val at0 = calls.indexOf(notify)
        withTimeout(15_000) { while (outs.size <= at0) delay(100) } // its output lands just after the call
        val kdeId = Regex("""uint32 (\d+)""").find(outs[at0])!!.groupValues[1].toInt()
        if (realKde) assertEquals(pings.notifier.keyFor(kdeId)?.substringBefore(":"), "task", "KDE's id maps back to the reminder")
        withTimeout(30_000) { pings.handle(DbusEvent.Action(kdeId, doneAction!!)) } // it once deadlocked on the scheduler's lock
        withTimeout(20_000) { while (g.sync.db.get("SELECT coalesce(completed_on, '') FROM tasks WHERE id = ?", listOf(task.toString())) { it.getString(0)!! } == "") delay(300) }
        assertTrue(calls.any { it.contains("org.freedesktop.Notifications.CloseNotification") }, "the done reminder goes away")
        withTimeout(60_000) { while (g.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) delay(400) }
        assertEquals(1L, g.sync.db.get("SELECT count(*) FROM tasks WHERE id = ? AND completed_on IS NOT NULL", listOf(task.toString())) { it.getLong(0)!! })
        // Review focus 2: [done] again on the stale notification (already done) changes nothing.
        // (updated_at is the server's and may still sync down, so the check is the exact completion time + no upload.)
        // Postgres keeps microseconds, so the synced-down copy is compared to the millisecond.
        suspend fun completedAt() = g.sync.db.get("SELECT completed_at FROM tasks WHERE id = ?", listOf(task.toString())) { java.time.Instant.parse(it.getString(0)!!).truncatedTo(ChronoUnit.MILLIS) }
        val stamp = completedAt()
        withTimeout(30_000) { pings.handle(DbusEvent.Action(kdeId, doneAction)) }
        delay(1_500)
        assertEquals(stamp, completedAt(), "no second completion")
        assertEquals(0L, g.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! }, "nothing new to upload")
        // Review focus 4: pings off → nothing planned any more.
        assertTrue(g.reminderKeys.getString("scheduled") != null, "the journal/day-end pings were planned")
        pings.enabled = false
        withTimeout(10_000) { while (g.reminderKeys.getString("scheduled") != null) delay(200) }
        assertEquals(false, DesktopPings(g, {}, {}, run = { "(uint32 1,)" }, watchDbus = false).enabled, "the switch is kept")
        g.actions.run { runCatching { g.taskActions.deleteTask(task) } }
        g.sync.db.disconnect(); g.scope.cancel()
    }
}
