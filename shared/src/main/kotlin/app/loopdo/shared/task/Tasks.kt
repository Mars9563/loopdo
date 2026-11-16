package app.loopdo.shared.task

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/** How a recurring task repeats (separate from habits; spec 6.5). */
sealed interface RepeatRule {
    data object Daily : RepeatRule

    data class Weekly(val days: Set<DayOfWeek>) : RepeatRule {
        init { require(days.isNotEmpty()) { "pick at least one weekday" } }
    }

    /** Day 29–31 in a shorter month falls on that month's last day. */
    data class MonthlyOnDay(val day: Int) : RepeatRule {
        init { require(day in 1..31) { "day of month must be 1..31, was $day" } }
    }

    data class EveryNDays(val n: Int, val anchor: LocalDate) : RepeatRule {
        init { require(n >= 1) { "n must be at least 1, was $n" } }
    }
}

/** Occurrence dates of [rule] between [from] and [toInclusive], ascending. */
fun occurrences(rule: RepeatRule, from: LocalDate, toInclusive: LocalDate): List<LocalDate> {
    if (from > toInclusive) return emptyList()
    val range = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= toInclusive }
    return when (rule) {
        RepeatRule.Daily -> range.toList()
        is RepeatRule.Weekly -> range.filter { it.dayOfWeek in rule.days }.toList()
        is RepeatRule.EveryNDays -> range
            .filter { it >= rule.anchor && ChronoUnit.DAYS.between(rule.anchor, it) % rule.n == 0L }
            .toList()
        is RepeatRule.MonthlyOnDay ->
            generateSequence(YearMonth.from(from)) { it.plusMonths(1) }
                .takeWhile { it <= YearMonth.from(toInclusive) }
                .map { it.atDay(minOf(rule.day, it.lengthOfMonth())) }
                .filter { it in from..toInclusive }
                .toList()
    }
}

/** Name-based UUID version 5 (RFC 9562): SHA-1 of namespace bytes + UTF-8 name. */
fun uuidV5(namespace: UUID, name: String): UUID {
    val ns = ByteBuffer.allocate(16)
        .putLong(namespace.mostSignificantBits)
        .putLong(namespace.leastSignificantBits)
        .array()
    val sha = MessageDigest.getInstance("SHA-1").apply {
        update(ns)
        update(name.toByteArray(Charsets.UTF_8))
    }.digest()
    sha[6] = ((sha[6].toInt() and 0x0f) or 0x50).toByte() // version 5
    sha[8] = ((sha[8].toInt() and 0x3f) or 0x80).toByte() // RFC variant
    val buf = ByteBuffer.wrap(sha, 0, 16)
    return UUID(buf.long, buf.long)
}

/**
 * The id of a recurring task's instance on [date]. Deterministic, so two devices
 * creating the same instance offline produce the same row instead of a duplicate.
 */
fun instanceId(templateId: UUID, date: LocalDate): UUID = uuidV5(templateId, date.toString())

enum class TaskState { INBOX, UPCOMING, DUE_TODAY, LATE, DONE_ON_TIME, DONE_LATE }

/** [completedOn] is the LoopDo date the task was completed (frozen on completion). */
fun taskState(dueDate: LocalDate?, completedOn: LocalDate?, today: LocalDate): TaskState = when {
    completedOn != null -> if (dueDate == null || completedOn <= dueDate) TaskState.DONE_ON_TIME else TaskState.DONE_LATE
    dueDate == null -> TaskState.INBOX
    dueDate > today -> TaskState.UPCOMING
    dueDate == today -> TaskState.DUE_TODAY
    else -> TaskState.LATE
}

/** The original due date of a task that was or is late (the miss stays on record). */
fun lateSince(dueDate: LocalDate?, completedOn: LocalDate?, today: LocalDate): LocalDate? =
    when (taskState(dueDate, completedOn, today)) {
        TaskState.LATE, TaskState.DONE_LATE -> dueDate
        else -> null
    }

/**
 * The occurrence dates of a repeating task that still need their own task: those in
 * [start]..[today] after [generatedThrough] (the last date already created). Running it again,
 * or on another device with the same synced [generatedThrough], creates nothing twice.
 */
fun instancesToCreate(rule: RepeatRule, start: LocalDate, generatedThrough: LocalDate?, today: LocalDate): List<LocalDate> {
    val from = if (generatedThrough != null && generatedThrough >= start) generatedThrough.plusDays(1) else start
    return occurrences(rule, from, today)
}

