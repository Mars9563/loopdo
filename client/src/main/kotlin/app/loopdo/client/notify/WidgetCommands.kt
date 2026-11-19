package app.loopdo.client.notify

import app.loopdo.client.log.logE
import app.loopdo.client.log.logI
import app.loopdo.client.today.StaleDayException
import app.loopdo.client.today.TodayActions
import java.time.LocalDate
import java.util.UUID

/**
 * What a widget tap does: the same actions as the app (trusted clock, evidence, one
 * transaction each), then always a redraw. After a refused stale tap the redraw is how a
 * widget still showing yesterday heals itself.
 */
class WidgetCommands(
    private val actions: TodayActions,
    /** The day the app is ready on right now, or null when it isn't (signed out, blocked, restoring). */
    private val readyOn: suspend () -> LocalDate?,
    private val redraw: suspend () -> Unit,
) {
    suspend fun tickHabit(id: UUID, shownDate: LocalDate) = run { actions.tickHabit(id, shownDate) }
    suspend fun plus(id: UUID, shownDate: LocalDate, step: Int) = run { actions.tickHabit(id, shownDate, by = step) }
    /** `[ ]` on an open count habit: all done (today's target). */
    suspend fun complete(id: UUID, shownDate: LocalDate) = run { actions.completeHabit(id, shownDate) }
    /** `[x]` on a done habit: yes/no back to open (same day); a count habit is unchanged (−1 is in the app). */
    suspend fun untickHabit(id: UUID, shownDate: LocalDate) = run { actions.untickHabit(id, shownDate) }
    suspend fun toggleTask(id: UUID, done: Boolean) = run { if (done) actions.reopenTask(id) else actions.completeTask(id) }

    /** What one tap did. The widget ignores it; a reminder's buttons (M7) show "the day has ended." on STALE. */
    suspend fun handle(tap: WidgetTap): TapResult {
        // The row may be older than the app's state (sign-out, a refused account switch, day end):
        // act only if the app is ready on the very day the row showed, else just redraw (final review I5).
        val ready = runCatching { readyOn() }.getOrNull()
        if (ready != tap.date) {
            runCatching { redraw() }
            return if (ready == null) TapResult.FAILED else TapResult.STALE
        }
        val id = UUID.fromString(tap.id)
        return when (tap.kind) {
            TapKind.TICK -> tickHabit(id, tap.date)
            TapKind.PLUS -> plus(id, tap.date, tap.step)
            TapKind.COMPLETE -> complete(id, tap.date)
            TapKind.UNTICK -> untickHabit(id, tap.date)
            TapKind.TASK -> toggleTask(id, tap.done)
        }
    }

    private suspend fun run(block: suspend () -> Unit): TapResult {
        try {
            block()
            return TapResult.DONE
        } catch (e: StaleDayException) {
            logI("loopdo_widget", "stale tap refused; today is ${e.today}")
            return TapResult.STALE
        } catch (e: Exception) {
            logE("loopdo_widget", "widget action failed", e)
            return TapResult.FAILED
        } finally {
            redraw()
        }
    }
}

