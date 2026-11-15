package app.loopdo.android.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * Schedules the widget's two alarms, each with Android's smallest inexact window (10 min), so no
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

    suspend fun schedule(dayEndAt: Instant?, now: Instant = Instant.now()) {
        if (GlanceAppWidgetManager(context).getGlanceIds(LoopDoWidget::class.java).isEmpty()) return cancel()
        alarms.setWindow(AlarmManager.RTC, now.plus(REFRESH_EVERY).toEpochMilli(), WINDOW_MS, pending(CODE_REFRESH))
        if (dayEndAt != null && dayEndAt.plusSeconds(1) > now) {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, dayEndAt.plusSeconds(1).toEpochMilli(), WINDOW_MS, pending(CODE_DAY_END))
        }
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
