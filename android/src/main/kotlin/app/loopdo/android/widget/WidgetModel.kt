package app.loopdo.android.widget

import app.loopdo.android.onboarding.StartState
import app.loopdo.android.today.StreakLine
import app.loopdo.android.today.TodayModel
import app.loopdo.android.today.compact
import app.loopdo.android.today.countdown
import app.loopdo.android.today.short
import app.loopdo.shared.habit.HabitKind
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.Instant
import java.time.LocalDate

enum class Tone { TEXT, DIM, WARN }

/** One widget row: `[ ] title ... right`. [plusOne] = a count habit's `[+1]`. */
data class WidgetRow(
    val id: String, val isHabit: Boolean, val title: String, val done: Boolean,
    val right: String, val rightTone: Tone, val plusOne: Boolean,
)

/** Everything the locked widget shows (docs/design/widget/README.md), built only from the today rules. */
data class WidgetModel(
    val date: LocalDate, val dateLabel: String, val countdown: String, val dayEndAt: Instant,
    val streaks: StreakLine, val habitsDone: Int, val habitsTotal: Int, val habits: List<WidgetRow>,
    val tasksOpen: Int, val tasks: List<WidgetRow>, val journalWritten: Boolean,
)

sealed interface WidgetState {
    data object SignedOut : WidgetState
    data object Restoring : WidgetState
    data object NeedsSetup : WidgetState
    data class Ready(val model: WidgetModel) : WidgetState
}

fun widgetModel(t: TodayModel, dayEndAt: Instant): WidgetModel {
    // Open first, done rows sink (each part keeps the today screen's order).
    val habits = (t.habitsOpen + t.habitsDone).map { h ->
        val done = h.count >= h.target
        when {
            done -> WidgetRow(h.id, true, h.title, true, "${h.streak}d", Tone.TEXT, plusOne = false)
            h.kind == HabitKind.COUNT -> WidgetRow(h.id, true, h.title, false, "${h.count}/${h.target}", Tone.TEXT, plusOne = true)
            else -> WidgetRow(h.id, true, h.title, false, h.reminderTime?.let { "@$it" }.orEmpty(), Tone.DIM, plusOne = false)
        }
    }
    val late = t.late.map { WidgetRow(it.id, false, it.title, false, "!late " + it.lateSince!!.compact(), Tone.WARN, plusOne = false) }
    val dueToday = t.dueToday.sortedBy { it.done }.map {
        WidgetRow(it.id, false, it.title, it.done, if (it.done) "" else it.dueTime?.let { tm -> "@$tm" }.orEmpty(), Tone.DIM, plusOne = false)
    }
    return WidgetModel(
        date = t.date, dateLabel = t.date.short(), countdown = t.endsIn.countdown(), dayEndAt = dayEndAt,
        streaks = t.streaks, habitsDone = t.habitsDone.size, habitsTotal = t.habitsOpen.size + t.habitsDone.size, habits = habits,
        tasksOpen = late.size + dueToday.count { !it.done }, tasks = late + dueToday, journalWritten = t.journalToday,
    )
}

/** Nothing tickable until the app is ready: ticks before the first sync could collide with restored data. */
fun widgetState(start: StartState, today: TodayModel?, dayEndAt: Instant?): WidgetState = when (start) {
    StartState.SignedOut -> WidgetState.SignedOut
    StartState.NeedsOnboarding -> WidgetState.NeedsSetup
    StartState.Ready -> if (today != null && dayEndAt != null) WidgetState.Ready(widgetModel(today, dayEndAt)) else WidgetState.Restoring
    else -> WidgetState.Restoring
}

/**
 * The widget's state for a live Glance session: built once at the start and again after every
 * [changes] signal (database change or an explicit redraw). Glance keeps a session open and
 * only recomposes on update, so state read once at session start would go stale.
 */
fun widgetStates(changes: kotlinx.coroutines.flow.Flow<Unit>, snapshot: suspend () -> WidgetState): kotlinx.coroutines.flow.Flow<WidgetState> =
    changes.onStart { emit(Unit) }.conflate().map { snapshot() }
