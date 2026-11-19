package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import app.loopdo.client.habits.HabitDraft
import app.loopdo.client.notify.Button
import app.loopdo.client.notify.CH_JOURNAL
import app.loopdo.client.notify.Shown
import app.loopdo.desktop.notify.DbusEvent
import app.loopdo.desktop.notify.DesktopPings
import app.loopdo.shared.day.settingsAt
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import app.loopdo.shared.remind.Ping
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D6 exit (TEST USER): a yes/no and a count habit ring on the laptop at their time; [tick] ticks and closes it,
 * [+2] adds 2 and updates it in place; the journal nudge's inline reply becomes today's line.
 */
class HabitPingsLiveTest {
    @Test
    fun `habit pings and the journal reply`() = runBlocking {
        LiveTestUser.assumeLive()
        val g = DesktopGraph(Files.createTempDirectory("loopdo-hping").toFile())
        if (g.auth.session.first { it !is AuthState.Loading } !is AuthState.SignedIn) g.auth.signIn(LiveTestUser.email, LiveTestUser.password)
        withTimeout(20_000) { g.auth.session.first { it is AuthState.SignedIn } }
        withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        withTimeout(30_000) { while (g.anchorStore.current() == null) delay(300) }
        val api = RecordingNotifyApi() // real KDE notifications with a desktop session
        val pings = DesktopPings(g, {}, {}, api = api)
        suspend fun logCount(h: UUID) = g.sync.db.get("SELECT coalesce(sum(count), 0) FROM habit_logs WHERE habit_id = ?", listOf(h.toString())) { it.getLong(0)!! }

        val now = g.trustedClock.now().instant
        val zone = settingsAt(g.todayData.versionsNow(), now).zone
        val at = now.atZone(zone).plusMinutes(2).truncatedTo(ChronoUnit.MINUTES).toLocalTime()
        val tag = UUID.randomUUID().toString().take(5)
        val yes = g.habitActions.createHabit(HabitDraft("ping yes $tag", HabitKind.YES_NO, 1, null, 1, Recurrence.DAILY, emptySet(), at))
        val count = g.habitActions.createHabit(HabitDraft("ping count $tag", HabitKind.COUNT, 4, "pages", 2, Recurrence.DAILY, emptySet(), at))
        pings.start()

        // Both ring at their time (10 min before has passed, so only "at").
        withTimeout(240_000) { while (api.about("ping yes $tag").isEmpty() || api.about("ping count $tag").isEmpty()) delay(1_000) }
        val yesN = api.about("ping yes $tag").first()
        val tick = yesN.actions.firstOrNull { it.endsWith("|tick|1") }
        assertTrue(tick != null, "a [tick] button: ${yesN.actions}")
        withTimeout(30_000) { pings.handle(DbusEvent.Action(yesN.id, tick!!)) }
        withTimeout(20_000) { while (logCount(yes) < 1L) delay(300) }
        assertEquals(1L, g.sync.db.get("SELECT verified FROM habit_logs WHERE habit_id = ?", listOf(yes.toString())) { it.getLong(0)!! }, "a verified tick, like the today screen")
        withTimeout(10_000) { while (yesN.id !in api.closed) delay(200) }

        val countN = api.about("ping count $tag").first()
        val plus = countN.actions.firstOrNull { it.endsWith("|plus|2") }
        assertTrue(plus != null, "a [+2] button: ${countN.actions}")
        val before = api.about("ping count $tag").size
        withTimeout(30_000) { pings.handle(DbusEvent.Action(countN.id, plus!!)) }
        withTimeout(20_000) { while (logCount(count) < 2L) delay(300) }
        // Still 2/4: the same KDE notification is updated in place (same id), quietly.
        withTimeout(10_000) { while (api.about("ping count $tag").size <= before) delay(200) }
        val update = api.about("ping count $tag").last()
        assertEquals(countN.id, update.id, "replaces the shown notification")
        assertEquals(true, update.hints["suppress-sound"]?.value)

        // The journal nudge's reply field (posted directly: the real one rings at 21:00). Emoji with a ZWJ and quotes
        // arrive as typed (final review I3).
        val day = pings.today()!!
        val text = "line from the laptop ping test $tag, it's \"fine\" 🏃‍♂️"
        assertTrue(pings.notifier.post(9_001, Shown(CH_JOURNAL, "nothing written today.", "", listOf(Button("[write]", "write"))),
            Reminder("journal:-:$day:at", ReminderKind.JOURNAL, null, day, now, Ping.AT)))
        val jId = api.posted.last().id
        withTimeout(30_000) { pings.handle(DbusEvent.Reply(jId, "  $text\n")) }
        withTimeout(20_000) {
            while (g.sync.db.get("SELECT count(*) FROM journal_lines WHERE text = ?", listOf(text)) { it.getLong(0)!! } == 0L) delay(300)
        }
        // A reply to a notification that isn't ours (another app's id) writes nothing.
        withTimeout(30_000) { pings.handle(DbusEvent.Reply(987_654, "not mine $tag")) }
        delay(1_000)
        assertEquals(0L, g.sync.db.get("SELECT count(*) FROM journal_lines WHERE text = ?", listOf("not mine $tag")) { it.getLong(0)!! })

        withTimeout(90_000) { while (g.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) delay(400) }
        // Tidy: this run's habits and earlier runs' (delete forever = hidden); the journal line stays (test user).
        g.sync.db.getAll("SELECT id FROM habits WHERE title LIKE 'ping % %' AND deleted_at IS NULL") { UUID.fromString(it.getString(0)!!) }.forEach { g.habitActions.hideHabit(it) }
        runCatching { pings.notifier.cancelAll() }
        withTimeout(60_000) { while (g.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) delay(400) }
        api.close(); g.sync.db.disconnect(); g.scope.cancel()
    }
}
