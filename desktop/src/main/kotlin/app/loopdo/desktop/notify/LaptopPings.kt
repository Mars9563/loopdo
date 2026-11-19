package app.loopdo.desktop.notify

import app.loopdo.client.log.logW
import app.loopdo.client.notify.AlarmSetter
import app.loopdo.client.notify.CH_DAY_END_NIGHT
import app.loopdo.client.notify.Poster
import app.loopdo.client.notify.ReminderStorage
import app.loopdo.client.notify.Shown
import app.loopdo.client.notify.decodeReminder
import app.loopdo.client.notify.encodeReminder
import app.loopdo.client.notify.keepShownFrom
import app.loopdo.desktop.store.FileKeyValue
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

// D6: the laptop's side of the phone's reminder scheduler — a file store, boot-clock timers and KDE notifications.

/** What was scheduled and shown on this laptop (dataDir/reminders.json, next to D5's "reviewed:<date>" marks). */
class FileReminderStore(private val kv: FileKeyValue) : ReminderStorage {
    private fun set(key: String, v: Collection<String>) = if (v.isEmpty()) kv.remove(key) else kv.putString(key, v.joinToString("\n"))
    private fun get(key: String) = kv.getString(key)?.split("\n")?.filter { it.isNotEmpty() }.orEmpty()
    override fun scheduled(): List<Reminder> = get(SCHEDULED).mapNotNull(::decodeReminder).sortedBy { it.fireAt }
    override fun setScheduled(rs: List<Reminder>) = set(SCHEDULED, rs.map(::encodeReminder))
    override fun shown(): Set<String> = get(SHOWN).toSet()
    override fun markShown(key: String) = set(SHOWN, shown() + key)
    override fun unmarkShown(key: String) = set(SHOWN, shown() - key)
    override fun pruneShown(day: LocalDate) = set(SHOWN, keepShownFrom(shown(), day))
    override fun clear() { kv.remove(SCHEDULED); kv.remove(SHOWN) }
    private companion object { const val SCHEDULED = "scheduled"; const val SHOWN = "shown" }
}

/**
 * One timer per key on the boot clock (CLOCK_BOOTTIME keeps counting in suspend): every [checkEveryMs] the due ones
 * fire, so a ping that came due while the laptop slept rings within seconds of waking (the scheduler's catch-up
 * rules decide whether it still applies).
 */
class TimerAlarms(
    scope: CoroutineScope,
    private val elapsedNow: () -> Long,
    private val checkEveryMs: Long = 15_000,
    private val onFire: suspend (String) -> Unit,
) : AlarmSetter {
    private val deadlines = ConcurrentHashMap<String, Long>()

    override fun set(key: String, elapsedMs: Long) { deadlines[key] = elapsedMs }
    override fun cancel(key: String) { deadlines.remove(key) }

    init {
        scope.launch {
            while (true) {
                delay(checkEveryMs)
                val now = elapsedNow()
                deadlines.filterValues { it <= now }.keys.sortedBy { deadlines[it] }.forEach { k ->
                    if (deadlines.remove(k) != null) runCatching { onFire(k) }.onFailure { logW("loopdo_pings", "a reminder failed", it) }
                }
            }
        }
    }
}

/** A GVariant string literal for gdbus ('…' with \ and ' escaped). */
fun gv(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

/**
 * KDE notifications through `gdbus call` on org.freedesktop.Notifications (no D-Bus library): the reminder's buttons
 * as actions ("key|action|step"), an inline reply field on the journal nudge, quiet re-posts and night warnings
 * without sound. [run] executes gdbus and returns its stdout (a fake in tests).
 */
class KdeNotifier(private val run: (List<String>) -> String) : Poster {
    /** Our notification id → KDE's, and KDE's → the reminder key (for buttons and replies). */
    private val kde = ConcurrentHashMap<Int, Int>()
    private val keys = ConcurrentHashMap<Int, String>()

    fun keyFor(kdeId: Int): String? = keys[kdeId]

    override fun post(id: Int, shown: Shown, r: Reminder): Boolean {
        val actions = buildList {
            shown.buttons.forEach { b -> add("${r.key}|${b.action}|${b.step}"); add(b.label) }
            if (r.kind == ReminderKind.JOURNAL) { add("inline-reply"); add("reply") }
        }
        val hints = buildList {
            add("'desktop-entry': <'loopdo'>")
            if (shown.silentUpdate || shown.channel == CH_DAY_END_NIGHT) add("'suppress-sound': <true>")
            if (r.kind == ReminderKind.JOURNAL) add("'x-kde-reply-placeholder-text': <'a line for today…'>")
        }
        val out = runCatching {
            run(listOf("gdbus", "call", "--session", "--dest", "org.freedesktop.Notifications", "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.Notify",
                gv("loopdo_"), (kde[id] ?: 0).toString(), gv("loopdo"), gv(shown.title), gv(shown.body),
                actions.joinToString(", ", "[", "]") { gv(it) }, hints.joinToString(", ", "{", "}"),
                (shown.timeoutMs ?: 0L).toString()))
        }.getOrElse { logW("loopdo_pings", "notify failed", it); return false }
        val kdeId = Regex("""uint32 (\d+)""").find(out)?.groupValues?.get(1)?.toInt() ?: return false
        kde[id] = kdeId; keys[kdeId] = r.key
        return true
    }

    override fun cancel(id: Int) {
        val k = kde.remove(id) ?: return
        keys.remove(k)
        runCatching {
            run(listOf("gdbus", "call", "--session", "--dest", "org.freedesktop.Notifications", "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.CloseNotification", k.toString()))
        }
    }

    override fun cancelAll() { kde.keys.toList().forEach(::cancel) }
}

/** What `gdbus monitor` tells us. */
sealed interface DbusEvent {
    data class Action(val kdeId: Int, val action: String) : DbusEvent
    data class Reply(val kdeId: Int, val text: String) : DbusEvent
    data class Closed(val kdeId: Int) : DbusEvent
    /** logind: the laptop woke from suspend. */
    data object Resumed : DbusEvent
}

private val SIGNAL = Regex("""\.(ActionInvoked|NotificationReplied|NotificationClosed|PrepareForSleep) \((.*)\)\s*$""")

/** One line of `gdbus monitor` output → an event (null for anything else). */
fun parseDbusEvent(line: String): DbusEvent? {
    val m = SIGNAL.find(line) ?: return null
    val args = m.groupValues[2]
    val id = Regex("""^uint32 (\d+)""").find(args)?.groupValues?.get(1)?.toInt()
    fun str(): String? = Regex("""^uint32 \d+, (['"])(.*)\1$""").find(args)?.let { mm ->
        mm.groupValues[2].replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
    }
    return when (m.groupValues[1]) {
        "ActionInvoked" -> id?.let { i -> str()?.let { DbusEvent.Action(i, it) } }
        "NotificationReplied" -> id?.let { i -> str()?.let { DbusEvent.Reply(i, it) } }
        "NotificationClosed" -> id?.let { DbusEvent.Closed(it) }
        "PrepareForSleep" -> if (args.startsWith("false")) DbusEvent.Resumed else null
        else -> null
    }
}
