package app.loopdo.android.tasks

import app.loopdo.android.habits.short as dayShort
import app.loopdo.android.today.short
import app.loopdo.shared.task.RepeatRule
import app.loopdo.shared.task.occurrences
import java.time.LocalDate

// The task screens' rules (locked habits-tasks-v1 screens 5–8). Which dates a series falls on
// comes from the shared occurrences(); this file only arranges it for the screens.

/** The add sheet's [↻ repeat] chip: never → daily → weekly (today's weekday) → monthly (today's day) → every 2 days → never. */
fun repeatChipCycle(current: RepeatRule?, today: LocalDate): RepeatRule? = when (current) {
    null -> RepeatRule.Daily
    RepeatRule.Daily -> RepeatRule.Weekly(setOf(today.dayOfWeek))
    is RepeatRule.Weekly -> RepeatRule.MonthlyOnDay(today.dayOfMonth)
    is RepeatRule.MonthlyOnDay -> RepeatRule.EveryNDays(2, today)
    is RepeatRule.EveryNDays -> null
}

fun repeatChipLabel(rule: RepeatRule?): String = when (rule) {
    null -> "↻ repeat"
    RepeatRule.Daily -> "↻ daily"
    is RepeatRule.Weekly -> "↻ weekly"
    is RepeatRule.MonthlyOnDay -> "↻ monthly"
    is RepeatRule.EveryNDays -> "↻ every ${rule.n} days"
}

/** The first [n] dates of [rule] on or after [from] (a monthly day 31 falls on a shorter month's last day). */
fun nextDates(rule: RepeatRule, from: LocalDate, n: Int = 2): List<LocalDate> =
    generateSequence(from) { it.plusMonths(3) }.take(12) // up to 3 years ahead, in 3-month windows
        .flatMap { start -> occurrences(rule, start, start.plusMonths(3).minusDays(1)) }
        .take(n).toList()

/** `next: tue 1 dec, fri 1 jan` (task editor, under the repeat choice). */
fun nextLine(rule: RepeatRule, from: LocalDate): String = "next: " + nextDates(rule, from).joinToString(", ") { it.short() }

/** What a [save] in the task editor changes. */
enum class SaveScope {
    /** A one-off task. */
    ONE,
    /** One occurrence of a series: ask "this one" or "this and future". */
    ASK,
    /** The series itself (opened from the REPEATING section): this and future. */
    SERIES,
}

fun saveScope(isTemplate: Boolean, isInstance: Boolean): SaveScope = when {
    isTemplate -> SaveScope.SERIES
    isInstance -> SaveScope.ASK
    else -> SaveScope.ONE
}

/** A repeating task's template, as the REPEATING section needs it. */
data class TemplateRow(val id: String, val title: String, val rule: RepeatRule, val start: LocalDate, val generatedThrough: LocalDate?)

/** `pay rent · next tue 1 dec`. */
data class RepeatingRow(val id: String, val line: String)

/** Running series (stopped ones are left out), each with its next date after today (or its first, if it hasn't started). */
fun repeatingRows(templates: List<TemplateRow>, today: LocalDate): List<RepeatingRow> =
    templates.filter { it.generatedThrough != STOPPED }.sortedBy { it.title }.mapNotNull { t ->
        val next = nextDates(t.rule, maxOf(t.start, today.plusDays(1)), 1).firstOrNull() ?: return@mapNotNull null
        RepeatingRow(t.id, "${t.title} · next ${next.short()}")
    }

/** `every sun, wed, starting sun 22 nov` (add sheet, under [↻ weekly]); the start is the first date on or after [from]. */
fun repeatLine(rule: RepeatRule, from: LocalDate): String {
    val what = when (rule) {
        RepeatRule.Daily -> "every day"
        is RepeatRule.Weekly -> "every " + rule.days.sorted().joinToString(", ") { it.dayShort() }
        is RepeatRule.MonthlyOnDay -> "on day ${rule.day} of each month"
        is RepeatRule.EveryNDays -> "every ${rule.n} days"
    }
    val first = nextDates(rule, from, 1).firstOrNull() ?: return what
    return "$what, starting ${first.short()}"
}
