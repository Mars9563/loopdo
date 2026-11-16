package app.loopdo.android.widget

import android.content.Context
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Intent
import kotlinx.coroutines.launch
import app.loopdo.android.LoopDoApp
import app.loopdo.android.today.StaleDayException
import app.loopdo.android.today.TodayActions
import java.time.LocalDate
import java.util.UUID

/**
 * What a widget tap does: the same actions as the app (trusted clock, evidence, one
 * transaction each), then always a redraw. After a refused stale tap the redraw is how a
 * widget still showing yesterday heals itself.
 */
class WidgetCommands(private val actions: TodayActions, private val redraw: suspend () -> Unit) {
    suspend fun tickHabit(id: UUID, shownDate: LocalDate) = run { actions.tickHabit(id, shownDate) }
    suspend fun plusOne(id: UUID, shownDate: LocalDate) = run { actions.tickHabit(id, shownDate) }
    /** `[x]` on a done habit: yes/no back to open (same day); a count habit is unchanged (−1 is in the app). */
    suspend fun untickHabit(id: UUID, shownDate: LocalDate) = run { actions.untickHabit(id, shownDate) }
    suspend fun toggleTask(id: UUID, done: Boolean) = run { if (done) actions.reopenTask(id) else actions.completeTask(id) }

    suspend fun handle(tap: WidgetTap) {
        val id = UUID.fromString(tap.id)
        when (tap.kind) {
            TapKind.TICK -> tickHabit(id, tap.date)
            TapKind.PLUS -> plusOne(id, tap.date)
            TapKind.UNTICK -> untickHabit(id, tap.date)
            TapKind.TASK -> toggleTask(id, tap.done)
        }
    }

    private suspend fun run(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: StaleDayException) {
            Log.i("loopdo_widget", "stale tap refused; today is ${e.today}")
        } catch (e: Exception) {
            Log.e("loopdo_widget", "widget action failed", e)
        } finally {
            redraw()
        }
    }
}

enum class TapKind(val path: String) { TICK("tick"), PLUS("plus"), UNTICK("untick"), TASK("task") }

/** One widget tap target: what to do to which item, as shown on [date] ([done] = the row showed it done). */
data class WidgetTap(val kind: TapKind, val id: String, val date: LocalDate, val done: Boolean) {
    /** Android identifies a widget PendingIntent by its data, so each item gets its own (final review C1). */
    fun toUri(): String = "$SCHEME://tap/${kind.path}/$id?date=$date&done=$done"
}

private const val SCHEME = "loopdo-widget"

fun parseWidgetTap(uri: String?): WidgetTap? = runCatching {
    val u = java.net.URI(uri!!)
    require(u.scheme == SCHEME && u.host == "tap")
    val (kind, id) = u.path.trim('/').split('/').let { TapKind.entries.first { k -> k.path == it[0] } to it[1] }
    val q = u.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
    WidgetTap(kind, id, LocalDate.parse(q.getValue("date")), q.getValue("done").toBooleanStrict())
}.getOrNull()

/** Receives the widget's taps (not exported: only the widget's own PendingIntents reach it). */
class WidgetTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tap = parseWidgetTap(intent.dataString) ?: return
        val graph = (context.applicationContext as LoopDoApp).graph
        val done = goAsync()
        graph.scope.launch {
            try {
                graph.widgetCommands.handle(tap)
            } finally {
                done.finish()
            }
        }
    }
}
