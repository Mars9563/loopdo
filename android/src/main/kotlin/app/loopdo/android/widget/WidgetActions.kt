package app.loopdo.android.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
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

internal val IdKey = ActionParameters.Key<String>("id")
internal val DateKey = ActionParameters.Key<String>("shownDate")
internal val DoneKey = ActionParameters.Key<Boolean>("done")

private fun Context.commands() = (applicationContext as LoopDoApp).graph.widgetCommands
private fun ActionParameters.id() = UUID.fromString(this[IdKey]!!)
private fun ActionParameters.date() = LocalDate.parse(this[DateKey]!!)

class TickHabit : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        context.commands().tickHabit(parameters.id(), parameters.date())
}

class PlusOne : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        context.commands().plusOne(parameters.id(), parameters.date())
}

class UntickHabit : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        context.commands().untickHabit(parameters.id(), parameters.date())
}

class ToggleTask : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        context.commands().toggleTask(parameters.id(), parameters[DoneKey] ?: false)
}
