package app.loopdo.android.settings

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.day.settingsAt
import app.loopdo.shared.remind.DAY_END_LEADS
import app.loopdo.shared.remind.ReminderPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

// M9: the ~/settings screen (locked settings-v1 A/B/D) as plain rules, tested on the JVM.

/** The day settings in force at [now] (what [keep] sends back to cancel a waiting change). */
fun inForceNow(versions: List<DaySettingsVersion>, now: Instant): DaySettings = settingsAt(versions, now)

private fun waiting(versions: List<DaySettingsVersion>, now: Instant) = versions.filter { it.effectiveAt > now }.minByOrNull { it.effectiveAt }

/** "from thu 19 nov 04:00: day ends 06:00" (or the zone) while a change waits for today to end; null otherwise. */
fun pendingLine(versions: List<DaySettingsVersion>, now: Instant): String? {
    val next = waiting(versions, now) ?: return null
    val cur = inForceNow(versions, now)
    if (next.settings == cur) return null // what [keep] leaves behind: nothing will change (review I5)
    val start = next.effectiveAt.atZone(cur.zone).format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)).lowercase()
    val what = listOfNotNull(
        next.settings.dayEnd.takeIf { it != cur.dayEnd }?.let { "day ends $it" },
        next.settings.zone.takeIf { it != cur.zone }?.let { "time zone ${it.id.lowercase()}" },
    ).ifEmpty { listOf("no change") }.joinToString(", ")
    return "from $start: $what"
}

/** The `[keep 04:00]` button while a change waits. */
fun keepLabel(versions: List<DaySettingsVersion>, now: Instant): String? =
    waiting(versions, now)?.takeIf { it.settings != inForceNow(versions, now) }?.let { "keep ${inForceNow(versions, now).dayEnd}" }

/** The settings that will apply once today ends: the waiting change if any, else the current ones. */
private fun upcoming(versions: List<DaySettingsVersion>, now: Instant): DaySettings = waiting(versions, now)?.settings ?: inForceNow(versions, now)

/** A new day end on top of what is coming (so a waiting zone change isn't dropped). */
fun withDayEnd(versions: List<DaySettingsVersion>, now: Instant, hour: Int): DaySettings =
    upcoming(versions, now).copy(dayEnd = java.time.LocalTime.of(hour, 0))

/** A new zone on top of what is coming (so a waiting day-end change isn't dropped). */
fun withZone(versions: List<DaySettingsVersion>, now: Instant, zone: String): DaySettings =
    upcoming(versions, now).copy(zone = java.time.ZoneId.of(zone))

/** "v0.9" from "0.9.0" (locked mock A). */
fun versionLabel(versionName: String): String = "v" + versionName.split('.').take(2).joinToString(".")

/** profiles.settings with the reminders part replaced; every other key is kept as it was. */
fun withPrefs(settingsJson: String?, p: ReminderPrefs): String {
    val old = runCatching { Json.parseToJsonElement(settingsJson!!).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    val reminders = buildJsonObject {
        put("habits", p.habits)
        put("tasks", p.tasks)
        putJsonObject("journal") { put("on", p.journal); put("at", p.journalAt.toString()) }
        putJsonObject("dayEnd") { put("on", p.dayEnd); put("leadMinutes", p.dayEndLead.toMinutes()) }
        putJsonObject("weekReview") { put("on", p.weekReview); put("at", p.weekReviewAt.toString()) }
    }
    return JsonObject(old + ("reminders" to reminders)).toString()
}

/** ADMIN row (only the admin sees it); "[…]" until the server answered. */
fun adminRow(isAdmin: Boolean, signupsOpen: Boolean?): String? = when {
    !isAdmin -> null
    signupsOpen == null -> "[…]"
    signupsOpen -> "[open]"
    else -> "[closed]"
}

/** `[delete in 7 days]` works only once "delete" is typed (locked mock D). */
fun deleteEnabled(typed: String): Boolean = typed.trim().lowercase() == "delete"

fun nextLead(d: Duration): Duration = DAY_END_LEADS[(DAY_END_LEADS.indexOf(d).coerceAtLeast(0) + 1) % DAY_END_LEADS.size]
fun leadLabel(d: Duration): String = if (d.toMinutes() % 60 == 0L) "${d.toHours()}h" else "${d.toMinutes()}m"
