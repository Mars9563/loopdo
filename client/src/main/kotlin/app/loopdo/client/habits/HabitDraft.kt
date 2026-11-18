package app.loopdo.client.habits

import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import java.time.DayOfWeek
import java.time.LocalTime

/** What the habit editor saves (locked screens 3 and 4). For a yes/no habit [target] is 1. */
data class HabitDraft(
    val title: String,
    val kind: HabitKind,
    val target: Int,
    val unit: String?,
    val step: Int,
    val recurrence: Recurrence,
    val weekdays: Set<DayOfWeek>,
    val reminderTime: LocalTime?,
)

/**
 * The habit editor's checks, run BEFORE anything is written, so the editor can show the message next
 * to the field and keep what was typed. Throws IllegalArgumentException with that message.
 * Returns the cleaned draft: trimmed title and unit, yes/no -> target 1 / step 1 / no unit, and no
 * leftover weekday ticks on a daily habit.
 *
 * Owner's rules (2026-11-17): title 3..30 characters; a count habit's target is at least 2 (a target
 * of 1 is a yes/no habit); step at least 1. Database limits (migrations 0001 + 0007): unit at most 16
 * characters; WEEKDAYS needs at least one day; PAUSED is never an edit (a pause is its own action).
 */
fun checkDraft(d: HabitDraft): HabitDraft {
    val title = d.title.trim()
    require(title.isNotEmpty()) { "why don't you pick a title for the habit." }
    require(title.length >= MIN_TITLE) { "that title is too short. use at least $MIN_TITLE characters." }
    require(title.length <= MAX_TITLE) { "that title is too long. keep it under $MAX_TITLE characters." }

    require(d.recurrence != Recurrence.PAUSED) { "use [pause] to pause a habit." }
    require(d.recurrence != Recurrence.WEEKDAYS || d.weekdays.isNotEmpty()) { "pick at least one weekday." }

    if (d.kind == HabitKind.YES_NO) {
        return d.copy(title = title, target = 1, step = 1, unit = null, weekdays = daysFor(d))
    }
    require(d.target >= 2) { "if the habit just needs one step why complicate things keep it a yes/no habit." }
    require(d.step >= 1) { "my friend n+0 = n." }
    val unit = d.unit?.trim()?.ifEmpty { null }
    require(unit == null || unit.length <= MAX_UNIT) { "keep the unit under $MAX_UNIT characters, like \"words\" or \"pages\"." }
    return d.copy(title = title, unit = unit, weekdays = daysFor(d))
}

/** Weekday ticks only count for a WEEKDAYS habit (switching to daily leaves them behind). */
private fun daysFor(d: HabitDraft) = if (d.recurrence == Recurrence.WEEKDAYS) d.weekdays else emptySet()

private const val MIN_TITLE = 3
private const val MAX_TITLE = 30
private const val MAX_UNIT = 16 // habits.unit check (migration 0007)
