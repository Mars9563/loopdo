package app.loopdo.client.notify

import app.loopdo.client.stats.StatsRows
import app.loopdo.shared.stats.FocusResult
import app.loopdo.shared.stats.focusResult
import app.loopdo.shared.stats.weekReview
import app.loopdo.shared.stats.weekStart
import java.time.LocalDate

/** M8: the Sunday 20:00 review ping's state — the week's numbers and its focus so far ("gym 3/4"). */
fun weekPingState(rows: StatsRows, sunday: LocalDate, opened: Boolean): ItemState {
    val start = weekStart(sunday)
    val w = weekReview(rows.input, start, sunday)
    val focus = rows.focus.filter { it.weekStart == start }.map { f ->
        when (val r = focusResult(f, rows.input, sunday)) {
            is FocusResult.Habit -> "${f.title} ${r.done}/${r.goal}"
            is FocusResult.Task -> f.title + if (r.completedOn != null) " ✓" else ""
        }
    }
    return ItemState.Week(w.number, w.done, w.total, focus, opened)
}
