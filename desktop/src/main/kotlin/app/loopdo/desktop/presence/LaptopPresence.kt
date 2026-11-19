@file:Suppress("FunctionName")

package app.loopdo.desktop.presence

import app.loopdo.client.log.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import java.time.Duration
import java.time.Instant

// D7: the laptop says when it's in use, so the phone's pings go quiet while it rings here. "In use" = signed in,
// pings on, awake and the screen unlocked (Plasma on Wayland gives apps no idle time; KDE's own auto-lock after 5 min
// without input stands in for it — owner's OK, 2026-11-19).

private val AHEAD: Duration = Duration.ofMinutes(5)
private val REFRESH_WHEN_LEFT: Duration = Duration.ofMinutes(2)

/**
 * What to write next, or null for nothing: in use → [now] + 5 min whenever 2 min or less of the last one are left
 * (so about every 3 min); not in use → [now] once, if the last one still reaches into the future.
 */
fun nextActiveUntil(inUse: Boolean, now: Instant, written: Instant?): Instant? = when {
    inUse && (written == null || Duration.between(now, written) <= REFRESH_WHEN_LEFT) -> now.plus(AHEAD)
    !inUse && written != null && written > now -> now
    else -> null
}

/**
 * Keeps this laptop's presence row: checks every [tickMs] and at once on a lock change, sleep, wake-up or quit.
 * Writes go one at a time, so a lock that arrives while a "+5 min" is being written is written after it ("now" last).
 */
class LaptopPresence(
    private val scope: CoroutineScope,
    /** Writes this laptop's row; false when it couldn't (offline, signed out): not counted, tried again next time. */
    private val write: suspend (Instant) -> Boolean,
    /** Waits (bounded) for queued uploads to reach the server: the "now" on sleep and quit must leave the laptop. */
    private val flush: suspend () -> Unit = {},
    private val now: () -> Instant,
    /** The screen is locked (KDE's screensaver). */
    private val locked: suspend () -> Boolean,
    /** Signed in, not blocked, pings on. */
    private val active: () -> Boolean,
    tickMs: Long = 30_000,
) {
    private val lock = Mutex()
    private val _inUse = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** What the phone hears right now: this laptop's last word reaches into the future (the tray's status line). */
    val inUse: kotlinx.coroutines.flow.StateFlow<Boolean> = _inUse
    @Volatile private var written: Instant? = null
    @Volatile private var asleep = false
    @Volatile private var stopped = false

    init { scope.launch { while (true) { delay(tickMs); check() } } }

    private suspend fun check() = lock.withLock {
        val inUse = !stopped && !asleep && active() && !runCatching { locked() }.getOrDefault(true)
        nextActiveUntil(inUse, now(), written)?.let { at ->
            runCatching { if (write(at)) written = at }.onFailure { logW("loopdo_presence", "couldn't write presence", it) }
        }
        _inUse.value = written?.let { it > now() } ?: false
    }

    /** Something changed (pings switched, signed in/out): look again now. */
    fun poke() { scope.launch { check() } }
    fun lockChanged(@Suppress("UNUSED_PARAMETER") locked: Boolean) = poke()
    /** The laptop is about to sleep: "now", and its upload, before this returns (the sleep is held until then). */
    suspend fun goingToSleep() { asleep = true; check(); runCatching { flush() } }
    fun woke() { asleep = false; poke() }

    /** About to sign out: "now" (and its upload) while the account is still signed in; after that nothing is written. */
    suspend fun handBack() { stopped = true; check(); runCatching { flush() }; stopped = false }

    /** Quitting: the phone rings again from now, uploaded before the database closes. */
    suspend fun stop() { stopped = true; check(); runCatching { flush() } }
}

/**
 * Whether the laptop takes the pings: pings on, signed in, not blocked, and not in Do Not Disturb — with DND its
 * pings are silent, so the phone must ring ("one ping too many, never one too few"; final review I2).
 */
fun countsAsInUse(pingsOn: Boolean, signedIn: Boolean, blocked: Boolean, doNotDisturb: Boolean): Boolean =
    pingsOn && signedIn && !blocked && !doNotDisturb

/**
 * logind's "delay" lock on sleep (systemd-inhibit, held by a child process): logind waits (≤ 5 s) for its release, so
 * the "now" can upload before the laptop sleeps. [start] runs the command and returns how to stop it.
 */
class SleepInhibitor(private val start: (List<String>) -> () -> Unit = ::startProcess) {
    private var stop: (() -> Unit)? = null

    @Synchronized fun take() {
        if (stop != null) return
        stop = runCatching { start(listOf("systemd-inhibit", "--what=sleep", "--mode=delay", "--who=loopdo_", "--why=hand pings back to the phone",
            // Ends with loopdo_ however it ends (even killed), so no orphan keeps delaying every sleep.
            "tail", "--pid=${ProcessHandle.current().pid()}", "-f", "/dev/null")) }
            .onFailure { logW("loopdo_presence", "no sleep delay (the phone may stay quiet up to 5 min after a lid close)", it) }.getOrNull()
    }

    @Synchronized fun release() { stop?.let { runCatching(it) }; stop = null }

    private companion object {
        fun startProcess(cmd: List<String>): () -> Unit {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            return { p.destroy() }
        }
    }
}

/** KDE's screensaver: whether the screen is locked, and a signal when that changes. */
@DBusInterfaceName("org.freedesktop.ScreenSaver")
interface ScreenSaver : DBusInterface {
    fun GetActive(): Boolean
    class ActiveChanged(path: String, val active: Boolean) : DBusSignal(path, active)
}
