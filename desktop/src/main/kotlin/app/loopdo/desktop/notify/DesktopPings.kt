package app.loopdo.desktop.notify

import app.loopdo.client.auth.AuthState
import app.loopdo.client.log.logI
import app.loopdo.client.log.logW
import app.loopdo.client.notify.REPLAN
import app.loopdo.client.notify.ReminderData
import app.loopdo.client.notify.ReminderScheduler
import app.loopdo.client.notify.WidgetCommands
import app.loopdo.client.notify.parseKey
import app.loopdo.client.notify.weekPingState
import app.loopdo.client.onboarding.hasDaySettings
import app.loopdo.client.stats.StatsData
import app.loopdo.desktop.DesktopGraph
import app.loopdo.shared.day.dateResolver
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Runs a short command and returns its stdout (tests call gdbus with it). */
fun runCommand(args: List<String>): String {
    val p = ProcessBuilder(args).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); throw java.io.IOException("timed out: ${args.take(3)}") }
    if (p.exitValue() != 0) throw java.io.IOException(out.trim())
    return out
}

/**
 * The laptop rings the phone's reminders (D6): the shared scheduler with a file store, boot-clock timers and KDE
 * notifications; buttons go through the phone's WidgetCommands; the journal nudge's reply becomes a line on the
 * nudge's day; it replans on start, on every data change, on sign-in/out, at the day end and when the laptop wakes.
 * "pings on this laptop" (off) clears everything. [onWrite] opens quick add; [onOpen] shows the app.
 * [api] null: KDE on the session bus (and logind on the system bus for the wake-up); tests pass a fake.
 */
class DesktopPings(
    private val g: DesktopGraph, private val onWrite: () -> Unit, private val onOpen: () -> Unit,
    api: NotifyApi? = null,
) {
    private val keys = g.reminderKeys
    private val session: DBusConnection? = if (api != null) null else runCatching { DBusConnectionBuilder.forSessionBus().withShared(false).build() }
        .onFailure { logW("loopdo_pings", "no session bus: no pings on this laptop", it) }.getOrNull()
    private val soundFile by lazy { pingSound(g.dataDir) }
    val notifier = DbusNotifier(api ?: session?.let(::DbusNotifyApi) ?: NoNotifications, keys, sound = { soundFile })
    private val store = FileReminderStore(keys)

    /** The LoopDo date the app is ready on (signed in, not blocked, day settings present), else null. */
    suspend fun today(): LocalDate? {
        if (g.auth.session.value !is AuthState.SignedIn || g.sync.blocked.value != null) return null
        if (!g.sync.db.hasDaySettings().first()) return null
        return dateResolver(g.todayData.versionsNow())(g.trustedClock.now().instant)
    }

    // Launched, not awaited (like the phone's redrawWidget): a button's tick redraws from inside the scheduler's lock,
    // and replan takes that lock too, so awaiting it here would freeze every ping on the laptop.
    private val commands = WidgetCommands(g.actions, readyOn = { today() }, redraw = { g.scope.launch { refresh() } })
    private val alarms: TimerAlarms = TimerAlarms(g.scope, elapsedNow = { g.clocks.elapsedRealtimeMs() }, onOverdue = { refresh() }) { key ->
        if (key == REPLAN) refresh() else if (enabled) scheduler.fire(key)
    }
    private val scheduler = ReminderScheduler(
        ReminderData(g.sync.db) { sunday -> weekPingState(StatsData(g.sync.db).rows().first(), sunday, keys.getLong("reviewed:$sunday") != null) },
        store, alarms, notifier,
        today = { today() },
        trustedNow = { g.trustedClock.now().instant },
        elapsedNow = { g.clocks.elapsedRealtimeMs() },
        signedOut = { g.sync.blocked.value != null || g.auth.session.value is AuthState.SignedOut },
    ) { commands.handle(it) }

    var enabled: Boolean
        get() = keys.getLong(OFF) == null
        set(on) { if (on) keys.remove(OFF) else keys.putLong(OFF, 1L); g.scope.launch { refresh() }; presence?.poke() }

    /**
     * D7: this laptop's "in use" signal, only with a real desktop session (tests with a fake notification server
     * never write presence). Its device id is made once and kept in dataDir/presence.json.
     */
    private val presence: app.loopdo.desktop.presence.LaptopPresence? = session?.let { conn ->
        val kv = app.loopdo.desktop.store.FileKeyValue(java.io.File(g.dataDir, "presence.json"))
        val device = kv.getString("device_id")?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: java.util.UUID.randomUUID().also { kv.putString("device_id", it.toString()) }
        val actions = app.loopdo.client.presence.PresenceActions(g.sync.db, { (g.auth.session.value as? AuthState.SignedIn)?.userId }, device)
        val screen = runCatching { conn.getRemoteObject("org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", app.loopdo.desktop.presence.ScreenSaver::class.java) }.getOrNull()
        val connected = { g.sync.db.currentStatus.connected }
        app.loopdo.desktop.presence.LaptopPresence(
            g.scope,
            // Only while connected: offline it can't reach the phone anyway, and queued presence would pile up as
            // "changes waiting" and block an account switch (final review I3). Not written = tried again next tick.
            write = { at -> connected() && actions.setActiveUntil(at) },
            // The "now" on sleep and quit has to leave the laptop: wait (≤ 3 s) for the upload queue (final review I1).
            flush = {
                kotlinx.coroutines.withTimeoutOrNull(3_000) {
                    while (connected() && g.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) kotlinx.coroutines.delay(100)
                }
            },
            now = { g.trustedClock.now().instant },
            // No screensaver to ask: treat as locked, so the phone keeps ringing (never silent by mistake).
            locked = { screen?.GetActive() ?: true },
            active = {
                app.loopdo.desktop.presence.countsAsInUse(
                    pingsOn = enabled, signedIn = g.auth.session.value is AuthState.SignedIn,
                    blocked = g.sync.blocked.value != null, doNotDisturb = notifier.inhibited(),
                )
            },
        )
    }

    /** Holds the laptop's sleep for a moment, so "now" can upload first (released on PrepareForSleep, taken on wake). */
    private val sleepLock = presence?.let { app.loopdo.desktop.presence.SleepInhibitor().also { it.take() } }

    /** Before a sign-out: the phone rings again from now (D7), written while the account is still signed in. */
    suspend fun handBack() { presence?.handBack() }

    /** Quitting: the phone rings again from now, uploaded before the database closes; the sleep lock goes too. */
    suspend fun stopPresence() { presence?.stop(); sleepLock?.release() }

    /** Repeating tasks' new copies first (like the phone's redraw), then the reminders again. Never throws. */
    suspend fun refresh() {
        runCatching {
            // Repeating tasks' copies don't depend on pings: with pings off they were never made here (ultrareview).
            today()?.let { d -> runCatching { g.taskActions.ensureInstances(d) }.onFailure { logW("loopdo_tasks", "creating repeating tasks failed", it) } }
            if (!enabled) { scheduler.clearAll(); return }
            scheduler.replan()
        }.onFailure { logW("loopdo_pings", "replan failed", it) }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        g.scope.launch { refresh() }
        // Opening the Sunday review clears its ping at once, like the phone's replan after the mark (ultrareview).
        g.onReviewOpened = { g.scope.launch { refresh() } }
        // One failure must not end a collector: replans would stop for the rest of the session (final review M2).
        fun <T> Flow<T>.replanOnEach() = g.scope.launch { runCatching { collect { refresh() } }.onFailure { logW("loopdo_pings", "a replan trigger stopped", it) } }
        merge(
            g.todayData.rows().map { },
            g.sync.db.watch("SELECT settings FROM profiles") { it.getString(0).orEmpty() }.map { },
        ).debounce(500).replanOnEach()
        g.auth.session.drop(1).replanOnEach()
        g.sync.blocked.drop(1).replanOnEach()
        g.scope.launch { g.auth.session.collect { presence?.poke() } }
        g.scope.launch { g.sync.blocked.collect { presence?.poke() } }
        session?.let(::listen)
    }

    /** KDE's signals for our notifications, Plasma restarting, and logind's wake-up. */
    private fun listen(conn: DBusConnection) {
        fun on(e: DbusEvent) { g.scope.launch { runCatching { handle(e) }.onFailure { logW("loopdo_pings", "handling $e failed", it) } } }
        runCatching {
            conn.addSigHandler(Notifications.ActionInvoked::class.java) { s -> on(DbusEvent.Action(s.id.toLong(), s.actionKey)) }
            conn.addSigHandler(Notifications.NotificationReplied::class.java) { s -> on(DbusEvent.Reply(s.id.toLong(), s.text)) }
            conn.addSigHandler(Notifications.NotificationClosed::class.java) { s -> on(DbusEvent.Closed(s.id.toLong())) }
            conn.addSigHandler(DBus.NameOwnerChanged::class.java) { s -> if (s.name == DbusNotifyApi.DEST) on(DbusEvent.ServerChanged) }
            // D7: locking hands the pings back to the phone at once (and unlocking takes them again).
            conn.addSigHandler(app.loopdo.desktop.presence.ScreenSaver.ActiveChanged::class.java) { s -> presence?.lockChanged(s.active) }
        }.onFailure { logW("loopdo_pings", "can't follow KDE's notifications", it) }
        runCatching {
            val system = DBusConnectionBuilder.forSystemBus().withShared(false).build()
            system.addSigHandler(Login1Manager.PrepareForSleep::class.java) { s ->
                if (s.start) g.scope.launch { presence?.goingToSleep(); sleepLock?.release() }
                else { sleepLock?.take(); presence?.woke(); on(DbusEvent.Resumed) }
            }
        }.onFailure { logW("loopdo_pings", "can't follow suspend (the 15 s timer check still catches up)", it) }
    }

    suspend fun handle(e: DbusEvent) {
        when (e) {
            is DbusEvent.Action -> {
                // Ours only: the action carries its reminder key; a KDE id we don't know is another app's.
                val key = notifier.keyFor(e.kdeId)
                when {
                    e.action == "default" -> if (key != null) onOpen()
                    e.action == "inline-reply" -> Unit
                    else -> {
                        val p = e.action.split("|")
                        val (rKey, action) = (p.getOrNull(0) ?: return) to (p.getOrNull(1) ?: return)
                        if (key != null && key != rKey) return // a recycled id: not this reminder's notification
                        when (action) {
                            "write" -> onWrite()
                            "review" -> onOpen()
                            else -> if (enabled) scheduler.act(action, rKey, p.getOrNull(2)?.toIntOrNull() ?: 1)
                        }
                    }
                }
            }
            is DbusEvent.Reply -> reply(e)
            is DbusEvent.Closed -> notifier.closed(e.kdeId)
            DbusEvent.ServerChanged -> notifier.serverChanged()
            DbusEvent.Resumed -> refresh()
        }
    }

    /**
     * The journal nudge's reply: a line on the nudge's own day (a reply sent just after the day end still lands on
     * the day it was for; final review I2). Refused (an older day, too long, not ready): the text goes to that day's
     * quick-add draft and quick add opens, so nothing typed is lost.
     */
    private suspend fun reply(e: DbusEvent.Reply) {
        val key = notifier.keyFor(e.kdeId) ?: return // not ours
        val day = parseKey(key)?.takeIf { key.startsWith("journal:") }?.date ?: return
        val text = e.text.trim()
        if (text.isEmpty()) return
        runCatching { g.journalActions.addLines(day, text, null) }
            .onSuccess { logI("loopdo_pings", "journal line from the notification") }
            .onFailure { err ->
                logW("loopdo_pings", "journal reply refused: ${err.message}; kept as a draft")
                val today = runCatching { today() }.getOrNull()
                val target = if (today != null && day < today.minusDays(1)) today else day
                val old = g.drafts.line(target).first
                g.drafts.keep(target, if (old.isEmpty()) text else "$old\n$text", g.trustedClock.now().instant, earlierDay = today != null && target < today)
                onWrite()
            }
        refresh() // the nudge goes away once the journal has a line
    }

    private companion object { const val OFF = "pings_off" }
}

/** No session bus: nothing can be shown (logged once at start). */
private object NoNotifications : NotifyApi {
    override fun notify(replaces: Long, summary: String, body: String, actions: List<String>, hints: Map<String, org.freedesktop.dbus.types.Variant<*>>, timeoutMs: Int): Long =
        throw IllegalStateException("no session bus")
    override fun close(id: Long) = Unit
    override fun server(): String? = null
}
