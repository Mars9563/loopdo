package app.loopdo.shared.task

import java.time.DayOfWeek
import java.time.LocalDate

/*
 * The stored form of a repeat rule (tasks.repeat_rule, jsonb). Written by hand because :shared has
 * no dependencies; only these four shapes exist:
 *   {"type":"daily"}  {"type":"weekly","days":[1,3,5]}  {"type":"monthly","day":1}
 *   {"type":"every_n_days","n":3,"anchor":"2026-11-16"}
 */

fun RepeatRule.toJson(): String = when (this) {
    RepeatRule.Daily -> """{"type":"daily"}"""
    is RepeatRule.Weekly -> """{"type":"weekly","days":[${days.map { it.value }.sorted().joinToString(",")}]}"""
    is RepeatRule.MonthlyOnDay -> """{"type":"monthly","day":$day}"""
    is RepeatRule.EveryNDays -> """{"type":"every_n_days","n":$n,"anchor":"$anchor"}"""
}

/** The rule in [json], or null if it isn't one of the four shapes (or breaks a rule's own checks). */
fun parseRepeatRule(json: String): RepeatRule? = runCatching {
    fun field(name: String, value: String) = Regex(""""$name"\s*:\s*$value""").find(json)?.groupValues?.get(1)
    when (field("type", """"([a-z_]+)"""")) {
        "daily" -> RepeatRule.Daily
        "weekly" -> RepeatRule.Weekly(
            field("days", """\[([0-9,\s]*)]""")!!.split(',').filter { it.isNotBlank() }.map { DayOfWeek.of(it.trim().toInt()) }.toSet(),
        )
        "monthly" -> RepeatRule.MonthlyOnDay(field("day", """(\d+)""")!!.toInt())
        "every_n_days" -> RepeatRule.EveryNDays(field("n", """(\d+)""")!!.toInt(), LocalDate.parse(field("anchor", """"([0-9-]+)"""")!!))
        else -> null
    }
}.getOrNull()
