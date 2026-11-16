package app.loopdo.android.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import app.loopdo.android.LoopDoApp
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Countdown refresh interval (owner chose `3h18m` text over a live clock, M4 Task 1). */
private val REFRESH_EVERY: Duration = Duration.ofMinutes(15)

/** When to redraw next: just after day end (so the new day shows) and every 15 minutes. */
fun widgetAlarms(now: Instant, dayEndAt: Instant): List<Instant> =
    (listOf(now.plus(REFRESH_EVERY)) + listOfNotNull(dayEndAt.plusSeconds(1).takeIf { it > now })).sorted()

/**
 * [widgetAlarms] as boot-clock times (SystemClock.elapsedRealtime): each target's distance from
 * trusted "now", added to the boot clock. The wall clock plays no part, so a hand-set clock can't
 * shift the alarms or make them re-arm in the past (final review I1).
 */
fun elapsedAlarms(trustedNow: Instant, elapsedNowMs: Long, dayEndAt: Instant): List<Long> =
    widgetAlarms(trustedNow, dayEndAt).map { toElapsed(trustedNow, elapsedNowMs, it) }

/** [target] (a trusted instant) as a boot-clock time. */
fun toElapsed(trustedNow: Instant, elapsedNowMs: Long, target: Instant): Long =
    elapsedNowMs + Duration.between(trustedNow, target).toMillis()

/**
 * Schedules the widget's two boot-clock alarms, each with Android's smallest inexact window (10 min), so no
 * exact-alarm permission is needed. Plain set()/setAndAllowWhileIdle() got windows of up to 1 h
 * on the A55. The day-end alarm may wake the phone. The 15-minute one is RTC: it never wakes the
 * phone and fires when the screen is next on. A stale tap is refused and redraws anyway (Task 2).
 */
class WidgetRefresher(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private fun pending(code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, WidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    suspend fun schedule(dayEndAt: Instant?, trustedNow: Instant) {
        if (GlanceAppWidgetManager(context).getGlanceIds(LoopDoWidget::class.java).isEmpty()) return cancel()
        val elapsed = SystemClock.elapsedRealtime()
        alarms.setWindow(AlarmManager.ELAPSED_REALTIME, elapsed + REFRESH_EVERY.toMillis(), WINDOW_MS, pending(CODE_REFRESH))
        val dayEndAlarm = dayEndAt?.plusSeconds(1)?.takeIf { it > trustedNow }?.let { toElapsed(trustedNow, elapsed, it) }
        if (dayEndAlarm != null) alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, dayEndAlarm, WINDOW_MS, pending(CODE_DAY_END))
        else alarms.cancel(pending(CODE_DAY_END))
    }

    private fun cancel() {
        alarms.cancel(pending(CODE_REFRESH))
        alarms.cancel(pending(CODE_DAY_END))
    }

    companion object {
        const val ACTION_REFRESH = "app.loopdo.android.widget.REFRESH"
        private const val CODE_REFRESH = 1
        private const val CODE_DAY_END = 2
        private const val WINDOW_MS = 10 * 60_000L // the minimum Android allows for inexact alarms
    }
}

/** Our alarms, plus clock/zone changes, boot and app updates (all allowed for manifest receivers). */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("loopdo_widget", "refresh: ${intent.action}")
        val graph = (context.applicationContext as LoopDoApp).graph
        val done = goAsync()
        graph.scope.launch {
            try {
                graph.redrawWidget()
            } finally {
                done.finish()
            }
        }
    }
}
