package app.loopdo.android.notify

import app.loopdo.client.notify.AlarmSetter
import app.loopdo.client.notify.Poster
import app.loopdo.client.notify.REPLAN
import app.loopdo.client.notify.ReminderStorage
import app.loopdo.client.notify.decodeReminder
import app.loopdo.client.notify.encodeReminder
import app.loopdo.client.notify.keepShownFrom

import app.loopdo.client.notify.Button
import app.loopdo.client.notify.CH_DAY_END
import app.loopdo.client.notify.CH_DAY_END_NIGHT
import app.loopdo.client.notify.CH_HABITS
import app.loopdo.client.notify.CH_JOURNAL
import app.loopdo.client.notify.CH_STATS
import app.loopdo.client.notify.CH_TASKS
import app.loopdo.client.notify.DAY_ENDED
import app.loopdo.client.notify.ReminderData
import app.loopdo.client.notify.Shown
import app.loopdo.client.notify.catchUp
import app.loopdo.client.notify.decide
import app.loopdo.client.notify.notificationId

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
import app.loopdo.client.notify.TapKind
import app.loopdo.client.notify.TapResult
import app.loopdo.client.notify.WidgetTap
import app.loopdo.client.notify.toElapsed
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

/** What was scheduled and what was shown, on this phone only (never synced). */
class ReminderStore(private val prefs: SharedPreferences) : ReminderStorage {
    override fun scheduled(): List<Reminder> = prefs.getStringSet(SCHEDULED, emptySet()).orEmpty().mapNotNull(::decodeReminder).sortedBy { it.fireAt }
    override fun setScheduled(rs: List<Reminder>) = prefs.edit().putStringSet(SCHEDULED, rs.map(::encodeReminder).toSet()).apply()
    override fun shown(): Set<String> = prefs.getStringSet(SHOWN, emptySet()).orEmpty()
    override fun markShown(key: String) = prefs.edit().putStringSet(SHOWN, shown() + key).apply()
    override fun unmarkShown(key: String) = prefs.edit().putStringSet(SHOWN, shown() - key).apply()
    override fun pruneShown(day: LocalDate) = prefs.edit().putStringSet(SHOWN, keepShownFrom(shown(), day)).apply()
    override fun clear() = prefs.edit().remove(SCHEDULED).remove(SHOWN).apply()

    private companion object {
        const val SCHEDULED = "scheduled"
        const val SHOWN = "shown"
    }
}

fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    listOf(CH_HABITS to "habits", CH_TASKS to "tasks", CH_JOURNAL to "journal", CH_DAY_END to "day end", CH_STATS to "weekly review").forEach { (id, name) ->
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
                .putExtra(MainActivity.EXTRA_TAB, when (r.kind) { ReminderKind.JOURNAL -> "journal"; ReminderKind.WEEK_REVIEW -> "stats"; else -> "today" })
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
        if (b.action == "review") PendingIntent.getActivity(
            context, notificationId(r) + 1, // not the body tap's request code (M7 review minor)
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, "stats").putExtra(MainActivity.EXTRA_REVIEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ) else if (b.action == "write") PendingIntent.getActivity(
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
    override fun cancelAll() = NotificationManagerCompat.from(context).cancelAll()
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
