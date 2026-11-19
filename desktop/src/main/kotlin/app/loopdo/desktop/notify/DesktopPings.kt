package app.loopdo.desktop.notify

import app.loopdo.client.auth.AuthState
import app.loopdo.client.log.logI
import app.loopdo.client.log.logW
import app.loopdo.client.notify.REPLAN
import app.loopdo.client.notify.ReminderData
import app.loopdo.client.notify.ReminderScheduler
import app.loopdo.client.notify.WidgetCommands
import app.loopdo.client.notify.weekPingState
import app.loopdo.client.onboarding.hasDaySettings
import app.loopdo.client.stats.StatsData
import app.loopdo.desktop.DesktopGraph
import app.loopdo.shared.day.dateResolver
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Runs a short command and returns its stdout (gdbus call). */
fun runCommand(args: List<String>): String {
    val p = ProcessBuilder(args).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
    if (p.exitValue() != 0) throw java.io.IOException(out.trim())
    return out
}

/**
 * The laptop rings the phone's reminders (D6): the shared scheduler with a file store, boot-clock timers and KDE
 * notifications; buttons go through the phone's WidgetCommands; the journal nudge's reply becomes a line; it replans on
 * start, on every data change, on sign-in/out, at the day end and when the laptop wakes. "pings on this laptop" (off)
 * clears everything. [onWrite] opens quick add; [onReview] shows the app on the review.
 */
class DesktopPings(
    private val g: DesktopGraph, private val onWrite: () -> Unit, private val onReview: () -> Unit,
    run: (List<String>) -> String = ::runCommand,
    private val watchDbus: Boolean = true,
) {
    private val keys = g.reminderKeys
    val notifier = KdeNotifier(run)
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
    private val alarms: TimerAlarms = TimerAlarms(g.scope, elapsedNow = { g.clocks.elapsedRealtimeMs() }) { key ->
        if (key == REPLAN) refresh() else scheduler.fire(key)
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
        set(on) { if (on) keys.remove(OFF) else keys.putLong(OFF, 1L); g.scope.launch { refresh() } }

    /** Repeating tasks' new copies first (like the phone's redraw), then the reminders again. */
    suspend fun refresh() {
        if (!enabled) {
            store.scheduled().forEach { alarms.cancel(it.key) }; alarms.cancel(REPLAN)
            notifier.cancelAll(); store.clear()
            return
        }
        today()?.let { d -> runCatching { g.taskActions.ensureInstances(d) }.onFailure { logW("loopdo_tasks", "creating repeating tasks failed", it) } }
        runCatching { scheduler.replan() }.onFailure { logW("loopdo_pings", "replan failed", it) }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        g.scope.launch { refresh() }
        g.scope.launch {
            merge(
                g.todayData.rows().map { },
                g.sync.db.watch("SELECT settings FROM profiles") { it.getString(0).orEmpty() }.map { },
            ).debounce(500).collect { refresh() }
        }
        g.scope.launch { g.auth.session.drop(1).collect { refresh() } }
        g.scope.launch { g.sync.blocked.drop(1).collect { refresh() } }
        if (!watchDbus) return
        // The gdbus helpers would outlive the app (quit, or the session's TERM) and pile up on every restart.
        Runtime.getRuntime().addShutdownHook(Thread { ProcessHandle.current().descendants().forEach { it.destroy() } })
        monitor(listOf("gdbus", "monitor", "--session", "--dest", "org.freedesktop.Notifications", "--object-path", "/org/freedesktop/Notifications"))
        monitor(listOf("gdbus", "monitor", "--system", "--dest", "org.freedesktop.login1", "--object-path", "/org/freedesktop/login1"))
    }

    /** Follows one `gdbus monitor`, restarting it if it ends (a session restart). */
    private fun monitor(cmd: List<String>) = g.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        while (true) {
            runCatching {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> parseDbusEvent(line)?.let { e -> g.scope.launch { handle(e) } } } }
            }.onFailure { logW("loopdo_pings", "monitor stopped", it) }
            delay(5_000)
        }
    }

    suspend fun handle(e: DbusEvent) {
        when (e) {
            is DbusEvent.Action -> {
                if (e.action == "default" || e.action == "inline-reply") return
                val (key, action, step) = e.action.split("|").let { Triple(it.getOrNull(0) ?: return, it.getOrNull(1) ?: return, it.getOrNull(2)?.toIntOrNull() ?: 1) }
                when (action) {
                    "write" -> onWrite()
                    "review" -> onReview()
                    else -> scheduler.act(action, key, step)
                }
            }
            is DbusEvent.Reply -> {
                val key = notifier.keyFor(e.kdeId) ?: return
                if (!key.startsWith("journal:")) return
                val day = today() ?: return
                runCatching { g.journalActions.addLines(day, e.text.trim()) }
                    .onSuccess { logI("loopdo_pings", "journal line from the notification") }
                    .onFailure { logW("loopdo_pings", "journal reply refused: ${it.message}") }
                refresh() // the nudge goes away once the journal has a line
            }
            is DbusEvent.Closed -> Unit
            DbusEvent.Resumed -> refresh()
        }
    }

    private companion object { const val OFF = "pings_off" }
}
