package app.loopdo.client.notify

import app.loopdo.shared.day.settingsAt
import app.loopdo.shared.remind.Ping
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import app.loopdo.shared.remind.isQuietHours
import app.loopdo.shared.remind.planReminders
import app.loopdo.shared.remind.reminderKey
import app.loopdo.shared.remind.replanAt
import app.loopdo.shared.remind.snoozeAt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate

// M7 → D6: a device rings its own reminders. The planner (shared/remind) says what should ring; the scheduler sets
// one alarm per reminder, and when one fires it asks the database again before showing anything. The phone (alarms,
// Android notifications) and the laptop (timers, KDE notifications) give it their own store, alarms and poster.

/** The alarm that plans again just after the day end (tomorrow becomes today). */
const val REPLAN = "replan"

/** What was scheduled and what was shown, on this device only (never synced). */
interface ReminderStorage {
    fun scheduled(): List<Reminder>
    fun setScheduled(rs: List<Reminder>)
    fun shown(): Set<String>
    fun markShown(key: String)
    fun unmarkShown(key: String)
    /** Drops shown keys of days before [day] (a key is "kind:id:date:ping"). */
    fun pruneShown(day: LocalDate)
    fun clear()
}

/** One stored reminder as text ("key|KIND|id|date|epochMs|PING"). */
fun encodeReminder(r: Reminder) = listOf(r.key, r.kind.name, r.itemId ?: "", r.date, r.fireAt.toEpochMilli(), r.ping.name).joinToString("|")
fun decodeReminder(s: String): Reminder? = runCatching {
    val p = s.split("|")
    Reminder(p[0], ReminderKind.valueOf(p[1]), p[2].ifEmpty { null }, LocalDate.parse(p[3]), Instant.ofEpochMilli(p[4].toLong()), Ping.valueOf(p[5]))
}.getOrNull()
/** Keeps the shown keys of [day] and later. */
fun keepShownFrom(shown: Set<String>, day: LocalDate): Set<String> =
    shown.filter { k -> k.split(":").getOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { it >= day } ?: false }.toSet()

/** Sets and cancels one alarm per key, on the boot clock. */
interface AlarmSetter {
    fun set(key: String, elapsedMs: Long)
    fun cancel(key: String)
}

/** Posts or removes a notification; post answers false when notifications are refused. */
interface Poster {
    fun post(id: Int, shown: Shown, r: Reminder): Boolean
    fun cancel(id: Int)
    /** Every reminder notification (sign-out, another account). */
    fun cancelAll()
}

class ReminderScheduler(
    private val data: ReminderData,
    private val store: ReminderStorage,
    private val alarms: AlarmSetter,
    private val poster: Poster,
    /** The LoopDo date the app is ready on, or null (signed out, another account, restoring): then nothing rings. */
    private val today: suspend () -> LocalDate?,
    private val trustedNow: () -> Instant,
    private val elapsedNow: () -> Long,
    /** True only when nobody is signed in (or another account is blocked); false while the session is still loading. */
    private val signedOut: suspend () -> Boolean = { true },
    /** A button's habit/task change, through the widget's commands (same rules, trusted clock, time proof). */
    private val tap: suspend (WidgetTap) -> TapResult = { TapResult.FAILED },
    /** D7, the phone: a laptop is in use right now, so pings go to the quiet channel. The laptop never asks (false). */
    private val laptopInUse: suspend () -> Boolean = { false },
) {
    private val lock = Mutex()

    private fun elapsedFor(at: Instant) = toElapsed(trustedNow(), elapsedNow(), at)

    /** Plans every reminder again: cancels the ones that no longer apply, sets the new ones, shows missed ones. */
    suspend fun replan() = lock.withLock {
        val day = today()
        val old = store.scheduled()
        if (day == null) {
            // Still loading (a cold boot): keep everything; the next redraw plans again (final review, minor 3).
            if (!signedOut()) return@withLock
            old.forEach { alarms.cancel(it.key) }
            poster.cancelAll() // also those caught up earlier (final review I1)
            alarms.cancel(REPLAN)
            store.clear()
            return@withLock
        }
        val now = trustedNow()
        val input = data.input(now)
        val snoozes = old.filter { it.ping == Ping.SNOOZE && it.fireAt > now }
        val plan = planReminders(now, input).filterNot { silenced(it, snoozes) } + snoozes
        val keys = plan.map { it.key }.toSet()
        old.filter { it.key !in keys }.forEach { alarms.cancel(it.key) }
        plan.forEach { alarms.set(it.key, elapsedFor(it.fireAt)) }
        alarms.set(REPLAN, elapsedFor(replanAt(now, input.versions)))
        val off = offKinds(input.prefs)
        val missed = catchUp(old, store.shown(), now, day).filter { it.kind !in off }
        // A kind switched off in settings: what it already shows goes away too (review I4).
        old.filter { it.kind in off }.forEach { poster.cancel(notificationId(it)) }
        // Notifications already up whose item is now done (in the app, the widget or on the other device) go away.
        old.filter { it.key in store.shown() && it.key !in missed.map { m -> m.key } }.forEach { r ->
            if (decide(r, data.state(r), day, quiet = false) == null) poster.cancel(notificationId(r))
        }
        missed.forEach { show(it, day, now) }
        // Today's reminders that were shown (caught-up ones included) stay known, so their buttons work and a
        // later replan can still clear them when the item is done elsewhere (final review I1).
        val shown = store.shown()
        store.setScheduled(plan + old.filter { it.key in shown && it.date == day && it.key !in keys })
        store.pruneShown(day.minusDays(1))
    }

    /** Everything off ("pings on this laptop: off"): no alarms, no notifications, nothing kept; under the lock, so
     *  a replan already running can't put them back (final review M1). */
    suspend fun clearAll() = lock.withLock {
        store.scheduled().forEach { alarms.cancel(it.key) }
        alarms.cancel(REPLAN)
        poster.cancelAll()
        store.clear()
    }

    /** [p] is another ping of a snoozed item, due before its snooze rings. */
    private fun silenced(p: Reminder, snoozes: List<Reminder>) =
        p.ping != Ping.SNOOZE && snoozes.any { it.ping == Ping.SNOOZE && notificationId(it) == notificationId(p) && p.fireAt < it.fireAt }

    /** An alarm went off: show [key]'s reminder if it still applies. */
    suspend fun fire(key: String) = lock.withLock {
        if (key in store.shown()) return@withLock // already shown by a catch-up
        val r = store.scheduled().firstOrNull { it.key == key } ?: return@withLock
        show(r, today(), trustedNow())
    }

    /** A notification button: [action] is "tick" | "plus" | "done" | "snooze" on reminder [key]. */
    suspend fun act(action: String, key: String, step: Int) = lock.withLock {
        val r = parseKey(key) ?: return@withLock
        val id = notificationId(r)
        if (action == "snooze") {
            val at = snoozeAt(trustedNow(), r.date, data.versions())
            poster.cancel(id)
            if (at == null) return@withLock // it would ring after the day ends
            val s = r.copy(key = reminderKey(r.kind, r.itemId, r.date, Ping.SNOOZE), fireAt = at, ping = Ping.SNOOZE)
            // A snooze means quiet until it rings: the item's pings before it are dropped (owner's test, 2026-11-18).
            val quiet = store.scheduled().filter { it.key == s.key || silenced(it, listOf(s)) }
            quiet.filter { it.key != s.key }.forEach { alarms.cancel(it.key) }
            store.setScheduled(store.scheduled() - quiet.toSet() + s)
            store.unmarkShown(s.key)
            alarms.set(s.key, elapsedFor(at))
            return@withLock
        }
        val kind = when (action) { "tick" -> TapKind.TICK; "plus" -> TapKind.PLUS; "done" -> TapKind.TASK; else -> return@withLock }
        val result = tap(WidgetTap(kind, r.itemId ?: return@withLock, r.date, done = false, step = step))
        if (result == TapResult.STALE) {
            poster.post(id, Shown(CH_HABITS.takeIf { r.kind == ReminderKind.HABIT } ?: CH_TASKS, DAY_ENDED, "", emptyList(), silentUpdate = true, timeoutMs = 5_000L), r)
            return@withLock
        }
        val again = decide(r, data.state(r), today(), quiet = false)
        if (again == null) poster.cancel(id) else poster.post(id, again.copy(silentUpdate = true), r)
    }

    private suspend fun show(r: Reminder, day: LocalDate?, now: Instant) {
        val quiet = isQuietHours(now, settingsAt(data.versions(), now).zone)
        val shown = decide(r, data.state(r), day, quiet)?.let { routeForLaptop(it, runCatching { laptopInUse() }.getOrDefault(false)) }
        if (shown == null) {
            poster.cancel(notificationId(r)) // an earlier ping of a now-done item
            return
        }
        if (poster.post(notificationId(r), shown, r)) store.markShown(r.key)
    }
}

/** The kinds the owner switched off (M9 settings). */
fun offKinds(p: app.loopdo.shared.remind.ReminderPrefs): Set<ReminderKind> = buildSet {
    if (!p.habits) add(ReminderKind.HABIT)
    if (!p.tasks) add(ReminderKind.TASK)
    if (!p.journal) add(ReminderKind.JOURNAL)
    if (!p.dayEnd) add(ReminderKind.DAY_END)
    if (!p.weekReview) add(ReminderKind.WEEK_REVIEW)
}

/** A reminder from its key ("kind:id:date:ping"); its time is not in the key and not needed for a button. */
fun parseKey(key: String): Reminder? = runCatching {
    val (kind, id, date, ping) = key.split(":")
    Reminder(key, ReminderKind.valueOf(kind.uppercase()), id.takeIf { it != "-" }, LocalDate.parse(date), Instant.EPOCH, Ping.valueOf(ping.uppercase()))
}.getOrNull()

