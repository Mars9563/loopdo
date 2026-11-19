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
    private val write: suspend (Instant) -> Unit,
    private val now: () -> Instant,
    /** The screen is locked (KDE's screensaver). */
    private val locked: suspend () -> Boolean,
    /** Signed in, not blocked, pings on. */
    private val active: () -> Boolean,
    tickMs: Long = 30_000,
) {
    private val lock = Mutex()
    @Volatile private var written: Instant? = null
    @Volatile private var asleep = false
    @Volatile private var stopped = false

    init { scope.launch { while (true) { delay(tickMs); check() } } }

    private suspend fun check() = lock.withLock {
        val inUse = !stopped && !asleep && active() && !runCatching { locked() }.getOrDefault(true)
        nextActiveUntil(inUse, now(), written)?.let { at ->
            runCatching { write(at); written = at }.onFailure { logW("loopdo_presence", "couldn't write presence", it) }
        }
    }

    /** Something changed (pings switched, signed in/out): look again now. */
    fun poke() { scope.launch { check() } }
    fun lockChanged(@Suppress("UNUSED_PARAMETER") locked: Boolean) = poke()
    fun sleeping() { asleep = true; poke() }
    fun woke() { asleep = false; poke() }

    /** Quitting: the phone rings again from now (waited for, before the database closes). */
    suspend fun stop() { stopped = true; check() }
}

/** KDE's screensaver: whether the screen is locked, and a signal when that changes. */
@DBusInterfaceName("org.freedesktop.ScreenSaver")
interface ScreenSaver : DBusInterface {
    fun GetActive(): Boolean
    class ActiveChanged(path: String, val active: Boolean) : DBusSignal(path, active)
}
