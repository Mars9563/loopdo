package app.loopdo.desktop

import app.loopdo.client.notify.Button
import app.loopdo.client.notify.CH_DAY_END_NIGHT
import app.loopdo.client.notify.CH_JOURNAL
import app.loopdo.client.notify.Shown
import app.loopdo.desktop.notify.DbusEvent
import app.loopdo.desktop.notify.FileReminderStore
import app.loopdo.desktop.notify.KdeNotifier
import app.loopdo.desktop.notify.TimerAlarms
import app.loopdo.desktop.notify.parseDbusEvent
import app.loopdo.desktop.store.FileKeyValue
import app.loopdo.shared.remind.Ping
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LaptopPingsTest {
    private val day = LocalDate.of(2026, 11, 19)
    private val r = Reminder("habit:h1:$day:at", ReminderKind.HABIT, "h1", day, Instant.parse("2026-11-19T12:30:00Z"), Ping.AT)

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
    fun `timers fire at their boot-clock deadline, also after a suspend jumped past it`() = runTest {
        var elapsed = 1_000_000L
        val fired = mutableListOf<String>()
        val t = TimerAlarms(backgroundScope, elapsedNow = { elapsed }, checkEveryMs = 15_000) { fired += it }
        t.set("a", 1_000_000L + 60_000)
        t.set("b", 1_000_000L + 3_600_000)
        advanceTimeBy(30_000); runCurrent()
        assertTrue(fired.isEmpty())
        elapsed += 61_000; advanceTimeBy(15_001); runCurrent()
        assertEquals(listOf("a"), fired)
        elapsed += 4 * 3_600_000; advanceTimeBy(15_001); runCurrent() // the laptop slept for hours
        assertEquals(listOf("a", "b"), fired)
        t.set("c", elapsed + 60_000); t.cancel("c"); elapsed += 120_000; advanceTimeBy(15_001); runCurrent()
        assertEquals(listOf("a", "b"), fired, "a cancelled timer stays quiet")
    }

    @Test
    fun `a notification goes to KDE with its buttons, a reply field for the journal, and replaces itself`() {
        val calls = mutableListOf<List<String>>()
        val n = KdeNotifier(run = { args -> calls += args; "(uint32 42,)" })
        val shown = Shown(CH_JOURNAL, "journal · nothing yet today", "it's 21:00. what happened today?", listOf(Button("write", "write")))
        val jr = r.copy(key = "journal:-:$day:at", kind = ReminderKind.JOURNAL, itemId = null)
        assertTrue(n.post(7, shown, jr))
        val c = calls.single()
        assertEquals("org.freedesktop.Notifications.Notify", c[c.indexOf("--method") + 1])
        val args = c.drop(c.indexOf("--method") + 2)
        assertEquals("'loopdo_'", args[0]); assertEquals("0", args[1])
        assertTrue(args[5].contains("'journal:-:$day:at|write|1'") && args[5].contains("'inline-reply'"), args[5])
        assertTrue(args[6].contains("x-kde-reply-placeholder-text"))
        n.post(7, shown.copy(silentUpdate = true), jr)
        assertEquals("42", calls[1].drop(calls[1].indexOf("--method") + 2)[1], "replaces KDE's notification 42")
        assertTrue(calls[1].drop(calls[1].indexOf("--method") + 2)[6].contains("'suppress-sound': <true>"))
        assertEquals(jr.key, n.keyFor(42))
        n.cancel(7)
        assertEquals(listOf("org.freedesktop.Notifications.CloseNotification", "42"), calls[2].drop(calls[2].indexOf("--method") + 1))
    }

    @Test
    fun `night day-end warnings are silent and quotes in text are escaped`() {
        val calls = mutableListOf<List<String>>()
        val n = KdeNotifier(run = { args -> calls += args; "(uint32 5,)" })
        n.post(1, Shown(CH_DAY_END_NIGHT, "it's the day's last hour", "don't forget 'gym'", emptyList()), r)
        val args = calls.single().drop(calls.single().indexOf("--method") + 2)
        assertEquals("'don\\'t forget \\'gym\\''", args[4])
        assertTrue(args[6].contains("'suppress-sound': <true>"))
    }

    @Test
    fun `kde's signals are read from gdbus monitor lines`() {
        assertEquals(DbusEvent.Action(12, "habit:h1:2026-11-19:at|tick|1"),
            parseDbusEvent("/org/freedesktop/Notifications: org.freedesktop.Notifications.ActionInvoked (uint32 12, 'habit:h1:2026-11-19:at|tick|1')"))
        assertEquals(DbusEvent.Reply(9, "long day, it's fine"),
            parseDbusEvent("/org/freedesktop/Notifications: org.freedesktop.Notifications.NotificationReplied (uint32 9, \"long day, it's fine\")"))
        assertEquals(DbusEvent.Closed(9), parseDbusEvent("/org/freedesktop/Notifications: org.freedesktop.Notifications.NotificationClosed (uint32 9, uint32 2)"))
        assertEquals(DbusEvent.Resumed, parseDbusEvent("/org/freedesktop/login1: org.freedesktop.login1.Manager.PrepareForSleep (false,)"))
        assertEquals(null, parseDbusEvent("/org/freedesktop/login1: org.freedesktop.login1.Manager.PrepareForSleep (true,)"))
    }
}
