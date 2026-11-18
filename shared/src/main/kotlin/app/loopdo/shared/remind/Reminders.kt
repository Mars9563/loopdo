package app.loopdo.shared.remind

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.day.dateResolver
import app.loopdo.shared.day.dayEndAt
import app.loopdo.shared.day.dayEndInstant
import app.loopdo.shared.day.settingsAt
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.habit.isDue
import app.loopdo.shared.task.RepeatRule
import app.loopdo.shared.task.instanceId
import app.loopdo.shared.task.occurrences
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

// M7 (spec docs/superpowers/specs/2026-11-18-m7-notifications-design.md): which reminders the phone should ring, as
// a pure function of the local data, so the same rules can serve the desktop later. Whether a reminder is still
// worth showing is decided again when it fires (the item may have been done meanwhile, on any device).

/** WEEK_REVIEW (M8): Sunday 20:00, unless that week's review was opened that Sunday. */
enum class ReminderKind { HABIT, TASK, JOURNAL, DAY_END, WEEK_REVIEW }

/** BEFORE = 10 min ahead, AT = on time, SNOOZE = a task's [snooze 30m]. */
enum class Ping { BEFORE, AT, SNOOZE }

/** One ring. [key] is stable, so planning again replaces the same alarm instead of adding one. */
data class Reminder(val key: String, val kind: ReminderKind, val itemId: String?, val date: LocalDate, val fireAt: Instant, val ping: Ping)

data class HabitPlan(val id: String, val reminderTime: LocalTime?, val schedules: List<HabitSchedule>, val archivedOn: LocalDate?, val deleted: Boolean)

/** A task with a due date AND time (untimed tasks never ping, owner 2026-11-18). */
data class TaskPlan(val id: String, val dueDate: LocalDate, val dueTime: LocalTime, val done: Boolean)

/** A repeating task's template with a time: its occurrences that are not rows yet still ring. */
data class SeriesPlan(val templateId: UUID, val rule: RepeatRule, val start: LocalDate, val generatedThrough: LocalDate?, val dueTime: LocalTime)

data class ReminderInput(
    val versions: List<DaySettingsVersion>,
    val habits: List<HabitPlan>,
    /** (habit id, LoopDo date) pairs already done. */
    val doneHabits: Set<Pair<String, LocalDate>>,
    val tasks: List<TaskPlan>,
    val series: List<SeriesPlan>,
    /** Days whose journal has something (the journal streak's rule). */
    val journalDays: Set<LocalDate>,
)

val BEFORE: Duration = Duration.ofMinutes(10)
val JOURNAL_AT: LocalTime = LocalTime.of(21, 0)
val DAY_END_LEAD: Duration = Duration.ofHours(1)
val SNOOZE: Duration = Duration.ofMinutes(30)
val WEEK_REVIEW_AT: LocalTime = LocalTime.of(20, 0)
private val QUIET_FROM: LocalTime = LocalTime.of(23, 0)
private val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)

fun reminderKey(kind: ReminderKind, itemId: String?, date: LocalDate, ping: Ping): String =
    "${kind.name.lowercase()}:${itemId ?: "-"}:$date:${ping.name.lowercase()}"

/**
 * When [time] happens on LoopDo [date]: the day runs from the previous day end to [date]'s day end, so a time
 * before the day end (e.g. 02:00 with day end 04:00) is on the next calendar morning.
 */
fun reminderInstant(date: LocalDate, time: LocalTime, s: DaySettings): Instant {
    val calendar = if (time >= s.dayEnd) date else date.plusDays(1)
    return calendar.atTime(time).atZone(s.zone).toInstant()
}

/** One LoopDo day as the planner sees it: its settings and its [start, end). */
private class Day(val date: LocalDate, val s: DaySettings, val start: Instant, val end: Instant) {
    operator fun contains(t: Instant) = t >= start && t < end
}

/** Today and tomorrow, each under the settings in force when it starts (a pending day-end change applies tomorrow). */
private fun days(now: Instant, versions: List<DaySettingsVersion>): List<Day> {
    val today = dateResolver(versions)(now)
    val sToday = settingsAt(versions, now)
    val todayEnd = dayEndAt(versions, now)
    val sTomorrow = settingsAt(versions, todayEnd)
    return listOf(
        Day(today, sToday, dayEndInstant(today.minusDays(1), sToday), todayEnd),
        Day(today.plusDays(1), sTomorrow, todayEnd, dayEndInstant(today.plusDays(1), sTomorrow)),
    )
}

/** Every reminder still ahead of [now] for today and tomorrow, earliest first. */
fun planReminders(now: Instant, input: ReminderInput): List<Reminder> {
    val out = mutableListOf<Reminder>()
    fun add(kind: ReminderKind, id: String?, day: Day, at: Instant, ping: Ping) {
        if (at in day && at > now) out += Reminder(reminderKey(kind, id, day.date, ping), kind, id, day.date, at, ping)
    }
    fun twoPings(kind: ReminderKind, id: String, day: Day, time: LocalTime) {
        val at = reminderInstant(day.date, time, day.s)
        add(kind, id, day, at.minus(BEFORE), Ping.BEFORE)
        add(kind, id, day, at, Ping.AT)
    }
    val taskIds = input.tasks.map { it.id }.toSet()
    for (day in days(now, input.versions)) {
        for (h in input.habits) {
            val time = h.reminderTime ?: continue
            if (h.deleted || (h.archivedOn != null && day.date >= h.archivedOn)) continue
            if (!isDue(h.schedules, day.date) || (h.id to day.date) in input.doneHabits) continue
            twoPings(ReminderKind.HABIT, h.id, day, time)
        }
        for (t in input.tasks) if (!t.done && t.dueDate == day.date) twoPings(ReminderKind.TASK, t.id, day, t.dueTime)
        for (s in input.series) {
            val from = maxOf(s.start, s.generatedThrough?.plusDays(1) ?: s.start)
            if (day.date !in occurrences(s.rule, from, day.date)) continue
            val id = instanceId(s.templateId, day.date).toString()
            if (id !in taskIds) twoPings(ReminderKind.TASK, id, day, s.dueTime)
        }
        if (day.date !in input.journalDays) add(ReminderKind.JOURNAL, null, day, reminderInstant(day.date, JOURNAL_AT, day.s), Ping.AT)
        add(ReminderKind.DAY_END, null, day, day.end.minus(DAY_END_LEAD), Ping.AT)
        if (day.date.dayOfWeek == java.time.DayOfWeek.SUNDAY) add(ReminderKind.WEEK_REVIEW, null, day, reminderInstant(day.date, WEEK_REVIEW_AT, day.s), Ping.AT)
    }
    return out.sortedWith(compareBy<Reminder>({ it.fireAt }, { it.key }))
}

/** When to plan again: just after today's day end (tomorrow becomes today and the day after enters the plan). */
fun replanAt(now: Instant, versions: List<DaySettingsVersion>): Instant = dayEndAt(versions, now).plusSeconds(1)

/** A [snooze 30m] from [now] for a reminder of [date]; null when that would already be past [date]'s day end. */
fun snoozeAt(now: Instant, date: LocalDate, versions: List<DaySettingsVersion>): Instant? =
    now.plus(SNOOZE).takeIf { dateResolver(versions)(it) == date }

/** 23:00–07:00 local: the day-end warning shows without sound (owner 2026-11-18). */
fun isQuietHours(t: Instant, zone: ZoneId): Boolean {
    val local = t.atZone(zone).toLocalTime()
    return local >= QUIET_FROM || local < QUIET_UNTIL
}
