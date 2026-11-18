package app.loopdo.android.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.loopdo.android.LoopDoApp
import app.loopdo.android.MainActivity
import app.loopdo.android.R
import app.loopdo.android.widget.TapKind
import app.loopdo.android.widget.TapResult
import app.loopdo.android.widget.WidgetTap
import app.loopdo.android.widget.toElapsed
import app.loopdo.shared.day.settingsAt
import app.loopdo.shared.remind.Ping
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import app.loopdo.shared.remind.isQuietHours
import app.loopdo.shared.remind.planReminders
import app.loopdo.shared.remind.reminderKey
import app.loopdo.shared.remind.replanAt
import app.loopdo.shared.remind.snoozeAt
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate

// M7: the phone rings its own reminders (spec §5). The planner (shared/remind) says what should ring; this file
// sets one exact alarm per reminder, and when one fires it asks the database again before showing anything.

/** The alarm that plans again just after the day end (tomorrow becomes today). */
const val REPLAN = "replan"

/** What was scheduled and what was shown, on this phone only (never synced). */
class ReminderStore(private val prefs: SharedPreferences) {
    private fun encode(r: Reminder) = listOf(r.key, r.kind.name, r.itemId ?: "", r.date, r.fireAt.toEpochMilli(), r.ping.name).joinToString("|")
    private fun decode(s: String): Reminder? = runCatching {
        val p = s.split("|")
        Reminder(p[0], ReminderKind.valueOf(p[1]), p[2].ifEmpty { null }, LocalDate.parse(p[3]), Instant.ofEpochMilli(p[4].toLong()), Ping.valueOf(p[5]))
    }.getOrNull()

    fun scheduled(): List<Reminder> = prefs.getStringSet(SCHEDULED, emptySet()).orEmpty().mapNotNull(::decode).sortedBy { it.fireAt }
    fun setScheduled(rs: List<Reminder>) = prefs.edit().putStringSet(SCHEDULED, rs.map(::encode).toSet()).apply()
    fun shown(): Set<String> = prefs.getStringSet(SHOWN, emptySet()).orEmpty()
    fun markShown(key: String) = prefs.edit().putStringSet(SHOWN, shown() + key).apply()
    fun unmarkShown(key: String) = prefs.edit().putStringSet(SHOWN, shown() - key).apply()
    /** Drops shown keys of days before [day] (a key is "kind:id:date:ping"). */
    fun pruneShown(day: LocalDate) =
        prefs.edit().putStringSet(SHOWN, shown().filter { k -> k.split(":").getOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { it >= day } ?: false }.toSet()).apply()
    fun clear() = prefs.edit().remove(SCHEDULED).remove(SHOWN).apply()

    private companion object {
        const val SCHEDULED = "scheduled"
        const val SHOWN = "shown"
    }
}

/** Sets and cancels one alarm per key, on the boot clock. */
interface AlarmSetter {
    fun set(key: String, elapsedMs: Long)
    fun cancel(key: String)
}

/** Posts or removes a notification; post answers false when notifications are refused. */
interface Poster {
    fun post(id: Int, shown: Shown, r: Reminder): Boolean
    fun cancel(id: Int)
}

class ReminderScheduler(
    private val data: ReminderData,
    private val store: ReminderStore,
    private val alarms: AlarmSetter,
    private val poster: Poster,
    /** The LoopDo date the app is ready on, or null (signed out, another account, restoring): then nothing rings. */
    private val today: suspend () -> LocalDate?,
    private val trustedNow: () -> Instant,
    private val elapsedNow: () -> Long,
    /** A button's habit/task change, through the widget's commands (same rules, trusted clock, time proof). */
    private val tap: suspend (WidgetTap) -> TapResult = { TapResult.FAILED },
) {
    private val lock = Mutex()

    private fun elapsedFor(at: Instant) = toElapsed(trustedNow(), elapsedNow(), at)

    /** Plans every reminder again: cancels the ones that no longer apply, sets the new ones, shows missed ones. */
    suspend fun replan() = lock.withLock {
        val day = today()
        val old = store.scheduled()
        if (day == null) {
            old.forEach { alarms.cancel(it.key); poster.cancel(notificationId(it)) }
            alarms.cancel(REPLAN)
            store.clear()
            return@withLock
        }
        val now = trustedNow()
        val input = data.input(now)
        val snoozes = old.filter { it.ping == Ping.SNOOZE && it.fireAt > now }
        val plan = planReminders(now, input) + snoozes
        val keys = plan.map { it.key }.toSet()
        old.filter { it.key !in keys }.forEach { alarms.cancel(it.key) }
        plan.forEach { alarms.set(it.key, elapsedFor(it.fireAt)) }
        alarms.set(REPLAN, elapsedFor(replanAt(now, input.versions)))
        val missed = catchUp(old, store.shown(), now, day)
        // Notifications already up whose item is now done (in the app, the widget or on the other device) go away.
        old.filter { it.key in store.shown() && it.key !in missed.map { m -> m.key } }.forEach { r ->
            if (decide(r, data.state(r), day, quiet = false) == null) poster.cancel(notificationId(r))
        }
        // Today's reminders that were shown stay known, so their buttons work and a later replan can still clear them.
        val shownToday = old.filter { it.key in store.shown() && it.date == day && it.key !in keys }
        store.setScheduled(plan + shownToday)
        store.pruneShown(day.minusDays(1))
        missed.forEach { show(it, day, now) }
    }

    /** An alarm went off: show [key]'s reminder if it still applies. */
    suspend fun fire(key: String) = lock.withLock {
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
            store.setScheduled(store.scheduled().filter { it.key != s.key } + s)
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
        val shown = decide(r, data.state(r), day, quiet)
        if (shown == null) {
            poster.cancel(notificationId(r)) // an earlier ping of a now-done item
            return
        }
        if (poster.post(notificationId(r), shown, r)) store.markShown(r.key)
    }
}

/** A reminder from its key ("kind:id:date:ping"); its time is not in the key and not needed for a button. */
fun parseKey(key: String): Reminder? = runCatching {
    val (kind, id, date, ping) = key.split(":")
    Reminder(key, ReminderKind.valueOf(kind.uppercase()), id.takeIf { it != "-" }, LocalDate.parse(date), Instant.EPOCH, Ping.valueOf(ping.uppercase()))
}.getOrNull()

/** The five channels (spec §5.2). Android fixes a channel's sound once created, so the night one is separate. */
fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    listOf(CH_HABITS to "habits", CH_TASKS to "tasks", CH_JOURNAL to "journal", CH_DAY_END to "day end").forEach { (id, name) ->
        nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(true) })
    }
    nm.createNotificationChannel(
        NotificationChannel(CH_DAY_END_NIGHT, "day end · night (silent)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        },
    )
}

/** Exact alarms on the boot clock (USE_EXACT_ALARM: loopdo_ is sideloaded, spec §5.2); a 1-min window if ever refused. */
class AndroidAlarmSetter(private val context: Context) : AlarmSetter {
    private val am = context.getSystemService(AlarmManager::class.java)

    private fun pending(key: String, flags: Int) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("$SCHEME://fire/${Uri.encode(key)}")),
        PendingIntent.FLAG_IMMUTABLE or flags,
    )

    override fun set(key: String, elapsedMs: Long) {
        val p = pending(key, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedMs, p)
        else am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedMs, 60_000L, p)
    }

    override fun cancel(key: String) {
        pending(key, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }
}

class AndroidPoster(private val context: Context) : Poster {
    override fun post(id: Int, shown: Shown, r: Reminder): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled() ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, if (r.kind == ReminderKind.JOURNAL) "journal" else "today")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, shown.channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setSubText(shown.channel.substringBefore(" ·"))
            .setContentTitle(shown.title)
            .apply { if (shown.body.isNotEmpty()) setContentText(shown.body) }
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(shown.silentUpdate)
            .apply { shown.timeoutMs?.let { setTimeoutAfter(it) } }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        shown.buttons.forEach { b -> n.addAction(0, b.label, buttonIntent(b, r)) }
        nm.notify(id, n.build())
        return true
    }

    private fun buttonIntent(b: Button, r: Reminder): PendingIntent =
        if (b.action == "write") PendingIntent.getActivity(
            context, notificationId(r),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, "journal").putExtra(MainActivity.EXTRA_WRITE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ) else PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_BUTTON)
                .setData(Uri.parse("$SCHEME://${b.action}/${Uri.encode(r.key)}?step=${b.step}")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    override fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)
}

const val ACTION_FIRE = "app.loopdo.android.notify.FIRE"
const val ACTION_BUTTON = "app.loopdo.android.notify.BUTTON"
private const val SCHEME = "loopdo-remind"

/** Our reminder alarms (and, from Task 6, boot / clock changes). Not exported. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as LoopDoApp).graph
        val key = intent.data?.lastPathSegment
        val done = goAsync()
        graph.scope.launch {
            try {
                when {
                    intent.action == ACTION_FIRE && key == REPLAN -> graph.reminders.replan()
                    intent.action == ACTION_FIRE && key != null -> graph.reminders.fire(key)
                    intent.action == ACTION_BUTTON && key != null ->
                        graph.reminders.act(intent.data?.host.orEmpty(), key, intent.data?.getQueryParameter("step")?.toIntOrNull() ?: 1)
                    else -> graph.reminders.replan()
                }
            } catch (e: Exception) {
                Log.e("loopdo_remind", "reminder ${intent.action} failed", e)
            } finally {
                done.finish()
            }
        }
    }
}
