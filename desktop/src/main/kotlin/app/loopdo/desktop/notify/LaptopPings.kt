@file:Suppress("FunctionName")

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
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

// D6: the laptop's side of the phone's reminder scheduler — a file store, boot-clock timers and KDE notifications
// over one long-lived D-Bus connection (dbus-java): KDE answers the sender of a notification, so buttons and replies
// come back to us as real strings, with no gdbus processes and no text escaping (final review I1, I3, I4).

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
 * fire. Ones overdue by more than two sweeps (the laptop slept over them) don't ring one by one: [onOverdue] plans
 * again once, and the scheduler's catch-up shows only the latest missed ping of an item (final review M3).
 */
class TimerAlarms(
    scope: CoroutineScope,
    private val elapsedNow: () -> Long,
    private val checkEveryMs: Long = 15_000,
    private val onOverdue: suspend () -> Unit = {},
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
                val due = deadlines.filterValues { it <= now }.entries.sortedBy { it.value }
                val (late, onTime) = due.partition { now - it.value > 2 * checkEveryMs }
                late.forEach { deadlines.remove(it.key) }
                if (late.isNotEmpty()) runCatching { onOverdue() }.onFailure { logW("loopdo_pings", "catching up failed", it) }
                onTime.forEach { (k, _) ->
                    if (deadlines.remove(k) != null) runCatching { onFire(k) }.onFailure { logW("loopdo_pings", "a reminder failed", it) }
                }
            }
        }
    }
}

/** org.freedesktop.Notifications, as KDE serves it. */
@DBusInterfaceName("org.freedesktop.Notifications")
interface Notifications : DBusInterface {
    fun Notify(appName: String, replacesId: UInt32, appIcon: String, summary: String, body: String, actions: List<String>, hints: Map<String, Variant<*>>, expireTimeout: Int): UInt32
    fun CloseNotification(id: UInt32)
    class ActionInvoked(path: String, val id: UInt32, val actionKey: String) : DBusSignal(path, id, actionKey)
    class NotificationClosed(path: String, val id: UInt32, val reason: UInt32) : DBusSignal(path, id, reason)
    class NotificationReplied(path: String, val id: UInt32, val text: String) : DBusSignal(path, id, text)
}

/** The notification server as the notifier needs it (a fake in tests). */
interface NotifyApi {
    fun notify(replaces: Long, summary: String, body: String, actions: List<String>, hints: Map<String, Variant<*>>, timeoutMs: Int): Long
    fun close(id: Long)
    /** Who serves notifications now (its unique bus name; a new one after Plasma restarts), null if nobody. */
    fun server(): String?
}

/** [NotifyApi] on a real session bus connection. */
class DbusNotifyApi(private val conn: DBusConnection) : NotifyApi {
    private val remote by lazy { conn.getRemoteObject(DEST, "/org/freedesktop/Notifications", Notifications::class.java) }
    private val dbus by lazy { conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java) }
    override fun notify(replaces: Long, summary: String, body: String, actions: List<String>, hints: Map<String, Variant<*>>, timeoutMs: Int) =
        remote.Notify("loopdo_", UInt32(replaces), "loopdo", summary, body, actions, hints, timeoutMs).toLong()
    override fun close(id: Long) = remote.CloseNotification(UInt32(id))
    override fun server(): String? = runCatching { dbus.GetNameOwner(DEST) }.getOrNull()
    companion object { const val DEST = "org.freedesktop.Notifications" }
}

/** KDE renders a body's markup: user text (habit and focus titles) is shown as typed (final review M5). */
fun escapeMarkup(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * KDE notifications: the reminder's buttons as actions ("key|action|step"), "default" (a click on it opens the app),
 * an inline reply field on the journal nudge, loopdo_'s sound on a new ping, quiet re-posts and night warnings, and
 * KDE's own popup time (then the history). Which KDE id is
 * which reminder is kept in [kv] for the notification server that gave it, so a notification from before a restart
 * of loopdo_ can still be closed, replaced and replied to; ids from another server (Plasma restarted, a new login)
 * are dropped, never acted on (final review I1).
 */
class DbusNotifier(
    private val api: NotifyApi, private val kv: FileKeyValue, private val bootId: () -> String = ::currentBootId,
    /** loopdo_'s own ping sound (owner, 2026-11-19), or null: then the sound theme's "message-new-instant". */
    private val sound: () -> java.io.File? = { null },
) : Poster {
    private data class Entry(val ours: Int, val kde: Long, val key: String)
    private val entries = mutableListOf<Entry>()

    init { load() }

    @Synchronized private fun load() {
        entries.clear()
        val saved = kv.getString(SERVER)
        if (saved == null || saved != serverTag()) { kv.remove(IDS); kv.remove(SERVER); return }
        kv.getString(IDS)?.split("\n")?.forEach { l ->
            val p = l.split("|", limit = 3)
            if (p.size == 3) runCatching { entries += Entry(p[0].toInt(), p[1].toLong(), p[2]) }
        }
    }

    @Synchronized private fun save() {
        if (entries.isEmpty()) { kv.remove(IDS); kv.remove(SERVER); return }
        kv.putString(SERVER, serverTag() ?: return)
        kv.putString(IDS, entries.joinToString("\n") { "${it.ours}|${it.kde}|${it.key}" })
    }

    private fun serverTag(): String? = api.server()?.let { "${bootId()}|$it" }

    /** The reminder key of KDE's notification [kdeId], if it is ours (else null: another app's, or a stale id). */
    @Synchronized fun keyFor(kdeId: Long): String? = entries.firstOrNull { it.kde == kdeId }?.key

    @Synchronized override fun post(id: Int, shown: Shown, r: Reminder): Boolean {
        val actions = buildList {
            add("default"); add("")
            shown.buttons.forEach { b -> add("${r.key}|${b.action}|${b.step}"); add(b.label) }
            if (r.kind == ReminderKind.JOURNAL) { add("inline-reply"); add("reply") }
        }
        val hints = buildMap<String, Variant<*>> {
            put("desktop-entry", Variant("loopdo"))
            // A new ping rings (KDE plays nothing for an app that names no sound); updates and night warnings don't.
            if (shown.silentUpdate || shown.channel == CH_DAY_END_NIGHT) {
                put("suppress-sound", Variant(true))
            } else {
                val f = sound()?.takeIf { it.isFile }
                if (f != null) put("sound-file", Variant(f.absolutePath)) else put("sound-name", Variant("message-new-instant"))
            }
            if (r.kind == ReminderKind.JOURNAL) put("x-kde-reply-placeholder-text", Variant("a line for today…"))
        }
        val replaces = entries.firstOrNull { it.ours == id }?.kde ?: 0L
        // -1: KDE's own popup time, then the history (owner, 2026-11-19).
        val kdeId = runCatching { api.notify(replaces, shown.title, escapeMarkup(shown.body), actions, hints, (shown.timeoutMs ?: -1L).toInt()) }
            .getOrElse { logW("loopdo_pings", "notify failed", it); return false }
        entries.removeAll { it.ours == id || it.kde == kdeId }
        entries += Entry(id, kdeId, r.key)
        save()
        return true
    }

    @Synchronized override fun cancel(id: Int) {
        val e = entries.firstOrNull { it.ours == id } ?: return
        entries.remove(e); save()
        runCatching { api.close(e.kde) }
    }

    @Synchronized override fun cancelAll() { entries.map { it.ours }.forEach(::cancel) }

    /** KDE closed one (dismissed, expired, or closed by us): forget it, so its id is never reused for us. */
    @Synchronized fun closed(kdeId: Long) { if (entries.removeAll { it.kde == kdeId }) save() }

    /** The notification server changed (Plasma restarted): its ids start again, ours are meaningless now. */
    @Synchronized fun serverChanged() { entries.clear(); kv.remove(IDS); kv.remove(SERVER) }

    private companion object { const val IDS = "kde_ids"; const val SERVER = "kde_server" }
}

fun currentBootId(): String = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")

/** What KDE and logind tell us. */
sealed interface DbusEvent {
    data class Action(val kdeId: Long, val action: String) : DbusEvent
    data class Reply(val kdeId: Long, val text: String) : DbusEvent
    data class Closed(val kdeId: Long) : DbusEvent
    /** Plasma's notification server restarted. */
    data object ServerChanged : DbusEvent
    /** logind: the laptop woke from suspend. */
    data object Resumed : DbusEvent
}

@DBusInterfaceName("org.freedesktop.login1.Manager")
interface Login1Manager : DBusInterface {
    class PrepareForSleep(path: String, val start: Boolean) : DBusSignal(path, start)
}

/** loopdo_'s ping sound, shipped in the jar and copied to dataDir/sounds/ (KDE plays a file path, not a jar entry). */
fun pingSound(dataDir: java.io.File): java.io.File? = runCatching {
    val bytes = DbusNotifier::class.java.getResourceAsStream("/sounds/ping.ogg")?.use { it.readBytes() } ?: return null
    val f = java.io.File(dataDir, "sounds/ping.ogg")
    if (!f.isFile || !f.readBytes().contentEquals(bytes)) { f.parentFile.mkdirs(); f.writeBytes(bytes) }
    f
}.getOrNull()
