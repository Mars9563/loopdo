package app.loopdo.android.notify

import app.loopdo.shared.remind.Ping
import app.loopdo.shared.remind.Reminder
import app.loopdo.shared.remind.ReminderKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// M7: what a reminder shows when its alarm fires, decided from the database as it is right then
// (spec §2–3). Pure, so the wording and the "show nothing" cases are tested on the JVM.

const val CH_HABITS = "habits"
const val CH_TASKS = "tasks"
const val CH_JOURNAL = "journal"
const val CH_DAY_END = "day end"
/** The day-end warning between 23:00 and 07:00: shown without sound (owner 2026-11-18). */
const val CH_DAY_END_NIGHT = "day end · night"

/** What the database says right now about a reminder's item. */
sealed interface ItemState {
    data class Habit(val title: String, val yesNo: Boolean, val count: Int, val target: Int, val step: Int, val dueToday: Boolean) : ItemState {
        val done get() = if (yesNo) count > 0 else count >= target
    }
    data class Task(val title: String, val time: LocalTime, val done: Boolean) : ItemState
    /** For the journal nudge and the day-end warning: undone due habits (in order) and whether the journal has a line. */
    data class Day(val undoneHabits: List<String>, val journalWritten: Boolean) : ItemState
    /** Deleted, or not on this phone. */
    data object Gone : ItemState
}

/** [action]: "tick" | "plus" | "done" | "snooze" | "write". */
data class Button(val label: String, val action: String, val step: Int = 1)

/** A notification to post. [silentUpdate] = an update after a button (no sound again). */
data class Shown(val channel: String, val title: String, val body: String, val buttons: List<Button>, val silentUpdate: Boolean = false)

const val DAY_ENDED = "the day has ended."

/**
 * The notification for [r], or null to show nothing: the app isn't ready ([today] null), [r]'s day has ended,
 * or the item is done, gone or not due. [quiet] = the night hours (only the day-end warning goes silent).
 */
fun decide(r: Reminder, state: ItemState, today: LocalDate?, quiet: Boolean): Shown? {
    if (today == null || r.date != today) return null
    return when (r.kind) {
        ReminderKind.HABIT -> {
            val h = state as? ItemState.Habit ?: return null
            if (h.done || !h.dueToday) return null
            val soon = r.ping == Ping.BEFORE
            if (h.yesNo) Shown(CH_HABITS, if (soon) "${h.title} in 10 min" else "${h.title} · now", "", listOf(Button("[tick]", "tick")))
            else Shown( // spec sketch: "water · 3/8"
                CH_HABITS, (if (soon) "${h.title} in 10 min" else h.title) + " · ${h.count}/${h.target}", "",
                listOf(Button("[+${h.step}]", "plus", h.step)),
            )
        }
        ReminderKind.TASK -> {
            val t = state as? ItemState.Task ?: return null
            if (t.done) return null
            Shown(
                CH_TASKS, "${t.title} · ${t.time}", if (r.ping == Ping.BEFORE) "in 10 min" else "now",
                listOf(Button("[done]", "done"), Button("[snooze 30m]", "snooze")),
            )
        }
        ReminderKind.JOURNAL -> {
            val d = state as? ItemState.Day ?: return null
            if (d.journalWritten) null else Shown(CH_JOURNAL, "nothing written today.", "", listOf(Button("[write]", "write")))
        }
        ReminderKind.DAY_END -> {
            val d = state as? ItemState.Day ?: return null
            val n = d.undoneHabits.size
            val parts = listOfNotNull(
                if (n == 0) null else if (n == 1) "1 habit" else "$n habits",
                if (d.journalWritten) null else "journal",
            )
            if (parts.isEmpty()) null
            else Shown(if (quiet) CH_DAY_END_NIGHT else CH_DAY_END, "1h left: ${parts.joinToString(" + ")} not done", d.undoneHabits.joinToString(", "), emptyList())
        }
    }
}

/**
 * Reminders whose time has passed without being shown (phone off, Android late): shown now if still today,
 * one per notification (the latest ping). Only reminders that were scheduled count, so a fresh install never floods.
 */
fun catchUp(scheduled: List<Reminder>, shown: Set<String>, now: Instant, today: LocalDate?): List<Reminder> {
    if (today == null) return emptyList()
    // The latest passed ping of each item decides: once the on-time ping was shown, the earlier one is never caught up.
    return scheduled.filter { it.fireAt <= now && it.date == today }
        .groupBy { notificationId(it) }.values.map { pings -> pings.maxBy { it.fireAt } }
        .filter { it.key !in shown }
        .sortedBy { it.fireAt }
}

/** One notification per item and day: the BEFORE, AT and SNOOZE pings replace each other. */
fun notificationId(r: Reminder): Int = "${r.kind}:${r.itemId}:${r.date}".hashCode()
