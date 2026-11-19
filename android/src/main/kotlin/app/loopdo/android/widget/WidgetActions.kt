package app.loopdo.android.widget

import app.loopdo.client.notify.TapKind
import app.loopdo.client.notify.TapResult
import app.loopdo.client.notify.WidgetTap

import android.content.Context
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Intent
import kotlinx.coroutines.launch
import app.loopdo.android.LoopDoApp
import app.loopdo.client.today.StaleDayException
import app.loopdo.client.today.TodayActions
import java.time.LocalDate
import java.util.UUID

/** Android identifies a widget PendingIntent by its data, so each item gets its own (final review C1). */
fun WidgetTap.toUri(): String = "$SCHEME://tap/${kind.path}/$id?date=$date&done=$done" + if (step != 1) "&step=$step" else ""

private const val SCHEME = "loopdo-widget"

fun parseWidgetTap(uri: String?): WidgetTap? = runCatching {
    val u = java.net.URI(uri!!)
    require(u.scheme == SCHEME && u.host == "tap")
    val (kind, id) = u.path.trim('/').split('/').let { TapKind.entries.first { k -> k.path == it[0] } to it[1] }
    val q = u.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
    WidgetTap(kind, id, LocalDate.parse(q.getValue("date")), q.getValue("done").toBooleanStrict(), q["step"]?.toInt() ?: 1)
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
