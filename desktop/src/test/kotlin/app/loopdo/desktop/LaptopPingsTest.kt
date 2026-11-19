package app.loopdo.desktop

import app.loopdo.client.notify.Button
import app.loopdo.client.notify.CH_DAY_END_NIGHT
import app.loopdo.client.notify.CH_JOURNAL
import app.loopdo.client.notify.CH_TASKS
import app.loopdo.client.notify.Shown
import app.loopdo.desktop.notify.DbusNotifier
import app.loopdo.desktop.notify.FileReminderStore
import app.loopdo.desktop.notify.NotifyApi
import app.loopdo.desktop.notify.TimerAlarms
import app.loopdo.desktop.store.FileKeyValue
import app.loopdo.shared.remind.Ping
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A notification server that records what it was asked; [owner] is its bus name (a new one = Plasma restarted). */
class FakeNotifyApi(var owner: String? = ":1.42") : NotifyApi {
    data class Call(val replaces: Long, val summary: String, val body: String, val actions: List<String>, val hints: Map<String, Variant<*>>, val timeoutMs: Int)
    val posted = mutableListOf<Call>()
    val closed = mutableListOf<Long>()
    private var next = 40L
    override fun notify(replaces: Long, summary: String, body: String, actions: List<String>, hints: Map<String, Variant<*>>, timeoutMs: Int): Long {
        posted += Call(replaces, summary, body, actions, hints, timeoutMs)
        return if (replaces != 0L) replaces else ++next
    }
    override fun close(id: Long) { closed += id }
    override fun server() = owner
}

@OptIn(ExperimentalCoroutinesApi::class)
class LaptopPingsTest {
    private val day = LocalDate.of(2026, 11, 19)
    private val r = Reminder("habit:h1:$day:at", ReminderKind.HABIT, "h1", day, Instant.parse("2026-11-19T12:30:00Z"), Ping.AT)
    private val jr = Reminder("journal:-:$day:at", ReminderKind.JOURNAL, null, day, Instant.parse("2026-11-19T15:30:00Z"), Ping.AT)
    private val journalShown = Shown(CH_JOURNAL, "journal", "nothing written today.", listOf(Button("[write]", "write")))
    private fun kv() = FileKeyValue(File(Files.createTempDirectory("loopdo-rem").toFile(), "reminders.json"))

    @Test
    fun `the store keeps scheduled and shown reminders next to the review marks`() {
        val f = File(Files.createTempDirectory("loopdo-rem").toFile(), "reminders.json")
        FileKeyValue(f).putLong("reviewed:2026-11-22", 1)
        FileReminderStore(FileKeyValue(f)).apply { setScheduled(listOf(r)); markShown(r.key) }
        val again = FileReminderStore(FileKeyValue(f))
        assertEquals(listOf(r), again.scheduled())
        assertEquals(setOf(r.key), again.shown())
        again.pruneShown(day.plusDays(1))
        assertTrue(again.shown().isEmpty())
        assertEquals(1L, FileKeyValue(f).getLong("reviewed:2026-11-22"), "the review mark survives")
        again.clear(); assertTrue(FileReminderStore(FileKeyValue(f)).scheduled().isEmpty())
    }

    @Test
    fun `timers fire at their deadline, and a long sleep over them plans again once instead of ringing each`() = runTest {
        var elapsed = 1_000_000L
        val fired = mutableListOf<String>()
        var overdue = 0
        val t = TimerAlarms(backgroundScope, elapsedNow = { elapsed }, checkEveryMs = 15_000, onOverdue = { overdue++ }) { fired += it }
        t.set("a", 1_000_000L + 60_000)
        t.set("b.before", 1_000_000L + 3_000_000); t.set("b.at", 1_000_000L + 3_600_000)
        advanceTimeBy(30_000); runCurrent()
        assertTrue(fired.isEmpty())
        elapsed += 61_000; advanceTimeBy(15_001); runCurrent()
        assertEquals(listOf("a"), fired, "on time (within a sweep)")
        elapsed += 4 * 3_600_000; advanceTimeBy(15_001); runCurrent() // the laptop slept for hours over b's two pings
        assertEquals(listOf("a"), fired, "no ring-ring of stale pings")
        assertEquals(1, overdue, "one replan; its catch-up shows the latest missed ping")
        t.set("c", elapsed + 60_000); t.cancel("c"); elapsed += 120_000; advanceTimeBy(15_001); runCurrent()
        assertEquals(listOf("a"), fired, "a cancelled timer stays quiet")
    }

    @Test
    fun `a notification goes to KDE with its buttons, a click target, a reply field for the journal, and replaces itself`() {
        val api = FakeNotifyApi()
        val n = DbusNotifier(api, kv(), bootId = { "boot1" })
        assertTrue(n.post(7, journalShown, jr))
        val c = api.posted.single()
        assertEquals(0L, c.replaces)
        assertEquals(listOf("default", "", "${jr.key}|write|1", "[write]", "inline-reply", "reply"), c.actions)
        assertEquals("a line for today…", c.hints["x-kde-reply-placeholder-text"]?.value)
        assertEquals("loopdo", c.hints["desktop-entry"]?.value)
        assertNull(c.hints["suppress-sound"], "a new ping rings")
        assertEquals("message-new-instant", c.hints["sound-name"]?.value, "with the theme's sound when loopdo_ has none")
        assertEquals(-1, c.timeoutMs, "KDE's own popup time (owner)")
        n.post(7, journalShown.copy(silentUpdate = true), jr)
        assertEquals(41L, api.posted[1].replaces, "replaces KDE's notification 41")
        assertEquals(true, api.posted[1].hints["suppress-sound"]?.value)
        assertNull(api.posted[1].hints["sound-name"], "an update is quiet")
        assertEquals(jr.key, n.keyFor(41))
        n.cancel(7)
        assertEquals(listOf(41L), api.closed)
        assertNull(n.keyFor(41))
    }

    @Test
    fun `loopdo_'s own sound file is named when it exists`() {
        val api = FakeNotifyApi()
        val f = File(Files.createTempDirectory("loopdo-snd").toFile(), "ping.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        DbusNotifier(api, kv(), sound = { f }).post(1, Shown(CH_TASKS, "pay rent · now", "", emptyList()), r)
        assertEquals(f.absolutePath, api.posted.single().hints["sound-file"]?.value)
        assertNull(api.posted.single().hints["sound-name"])
    }

    @Test
    fun `night warnings are silent and user text in the body is shown as typed`() {
        val api = FakeNotifyApi()
        DbusNotifier(api, kv()).post(1, Shown(CH_DAY_END_NIGHT, "the day ends in 1 hour", "gym & <read 30 min>", emptyList()), r)
        assertEquals("gym &amp; &lt;read 30 min&gt;", api.posted.single().body)
        assertEquals(true, api.posted.single().hints["suppress-sound"]?.value)
    }

    @Test
    fun `KDE ids survive a restart of loopdo_, but not of Plasma or the laptop`() {
        val store = kv()
        val api = FakeNotifyApi(owner = ":1.42")
        DbusNotifier(api, store, bootId = { "boot1" }).post(3, Shown(CH_TASKS, "pay rent · now", "", emptyList()), r)
        // loopdo_ restarted: the old notification is still ours (its buttons, its reply, closing it).
        val again = DbusNotifier(api, store, bootId = { "boot1" })
        assertEquals(r.key, again.keyFor(41))
        again.cancel(3)
        assertEquals(listOf(41L), api.closed)
        // Plasma restarted while loopdo_ was closed: KDE's ids start again, so the saved ones mean nothing.
        DbusNotifier(api, store, bootId = { "boot1" }).post(3, Shown(CH_TASKS, "pay rent · now", "", emptyList()), r)
        api.owner = ":1.99"
        assertNull(DbusNotifier(api, store, bootId = { "boot1" }).keyFor(42), "another server's id is never acted on")
        // Same on a new boot (bus names can repeat across logins).
        api.owner = ":1.42"
        val n = DbusNotifier(api, store, bootId = { "boot1" })
        n.post(3, Shown(CH_TASKS, "pay rent · now", "", emptyList()), r)
        assertNull(DbusNotifier(api, store, bootId = { "boot2" }).keyFor(43))
    }

    @Test
    fun `a closed or server-changed notification is forgotten`() {
        val api = FakeNotifyApi()
        val n = DbusNotifier(api, kv())
        n.post(1, journalShown, jr); n.post(2, Shown(CH_TASKS, "t", "", emptyList()), r)
        n.closed(41)
        assertNull(n.keyFor(41)); assertEquals(r.key, n.keyFor(42))
        n.serverChanged()
        assertNull(n.keyFor(42))
        n.cancelAll()
        assertTrue(api.closed.isEmpty(), "nothing left to close on the new server")
    }
}
