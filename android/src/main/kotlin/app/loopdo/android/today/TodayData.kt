package app.loopdo.android.today

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.Recurrence
import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Everything the today screen reads, as one snapshot of the phone's database. */
data class TodayRows(
    val versions: List<DaySettingsVersion>,
    val onboardedAt: Instant?,
    val habits: List<HabitRow>,
    val schedules: List<ScheduleRow>,
    val logs: List<LogRow>,
    val tasks: List<TaskRow>,
    val lists: List<ListRow>,
    val journalDays: Set<LocalDate>,
    val activeDays: Set<LocalDate>,
)

/** Postgres timestamptz as PowerSync syncs it ("2026-11-15 13:12:00+00") or ISO-8601. */
internal fun parseInstant(s: String): Instant {
    val iso = s.trim().replace(' ', 'T').replace(Regex("([+-]\\d{2})$"), "$1:00")
    return OffsetDateTime.parse(iso).toInstant()
}

/** smallint[] as JSON ("[1,3]") or Postgres ("{1,3}") text; ISO weekday numbers. */
/** habit_schedules.recurrence: "daily" | "weekdays" | "paused" (M5). */
internal fun parseRecurrence(s: String?): Recurrence = when (s) {
    "weekdays" -> Recurrence.WEEKDAYS
    "paused" -> Recurrence.PAUSED
    else -> Recurrence.DAILY
}

internal fun parseWeekdays(s: String?): Set<DayOfWeek> =
    Regex("\\d+").findAll(s.orEmpty()).map { DayOfWeek.of(it.value.toInt()) }.toSet()

private fun SqlCursor.str(i: Int) = getString(i)
private fun SqlCursor.date(i: Int) = getString(i)?.let { LocalDate.parse(it.take(10)) }
private fun SqlCursor.time(i: Int) = getString(i)?.let(LocalTime::parse)
private fun SqlCursor.int(i: Int) = getLong(i)?.toInt() ?: 0

data class JournalEntry(val text: String, val mood: Int?)

/** The days whose journal has something (the journal streak's rule); also the reminders' "journal written" (M7). */
internal const val JOURNAL_DAYS_SQL =
    "SELECT loopdo_date FROM journal_entries WHERE trim(text) <> '' OR id IN (SELECT entry_id FROM journal_photos)" +
        " OR id IN (SELECT entry_id FROM journal_lines WHERE deleted_at IS NULL AND trim(text) <> '')"

class TodayData(private val db: PowerSyncDatabase) {
    private val versionsSql = "SELECT effective_at, time_zone, day_end, floor_date FROM day_settings_versions"
    private fun version(c: SqlCursor) = DaySettingsVersion(
        parseInstant(c.str(0)!!), DaySettings(ZoneId.of(c.str(1)!!), LocalTime.parse(c.str(2)!!)), c.date(3),
    )

    /** [date]'s journal entry, or null if none was written. */
    suspend fun journalOn(date: LocalDate): JournalEntry? =
        db.getOptional("SELECT text, mood FROM journal_entries WHERE loopdo_date = ?", listOf(date.toString())) {
            JournalEntry(it.getString(0).orEmpty(), it.getLong(1)?.toInt())
        }

    /** For the actions: the settings history right now (not a watched copy). */
    suspend fun versionsNow(): List<DaySettingsVersion> = db.getAll(versionsSql) { version(it) }

    private val journalDaysSql = JOURNAL_DAYS_SQL

    fun rows(): Flow<TodayRows> {
        val flows: List<Flow<List<Any>>> = listOf(
            db.watch(versionsSql) { version(it) },
            db.watch("SELECT onboarded_at FROM profiles WHERE onboarded_at IS NOT NULL") { parseInstant(it.str(0)!!) },
            db.watch("SELECT id, title, kind, target, reminder_time, archived_on, sort_order, step, unit, deleted_at FROM habits") {
                HabitRow(it.str(0)!!, it.str(1)!!, if (it.str(2) == "count") HabitKind.COUNT else HabitKind.YES_NO,
                    it.int(3).coerceAtLeast(1), it.time(4), it.date(5), it.int(6),
                    step = it.int(7).coerceAtLeast(1), unit = it.str(8)?.takeIf { u -> u.isNotBlank() }, deletedAt = it.str(9)?.let(::parseInstant))
            },
            db.watch("SELECT habit_id, valid_from, recurrence, weekdays, target FROM habit_schedules") {
                ScheduleRow(it.str(0)!!, it.date(1)!!, parseRecurrence(it.str(2)), parseWeekdays(it.str(3)), it.getLong(4)?.toInt())
            },
            db.watch("SELECT habit_id, loopdo_date, count, verified FROM habit_logs") {
                LogRow(it.str(0)!!, it.date(1)!!, it.int(2), it.getLong(3) != 0L)
            },
            // Recurring templates are not tasks of their own (M4); long-done tasks are not shown.
            db.watch(
                """SELECT t.id, t.list_id, t.title, t.due_date, t.due_time, t.completed_on, t.sort_order, t.template_id, t.occurrence_date,
                          (SELECT max(c.occurrence_date) FROM tasks c WHERE t.template_id IS NOT NULL AND c.template_id = t.template_id AND c.completed_on IS NOT NULL)
                   FROM tasks t
                   WHERE t.repeat_rule IS NULL AND (t.completed_on IS NULL OR t.completed_on >= date('now', '-3 days'))""",
            ) { TaskRow(it.str(0)!!, it.str(1), it.str(2)!!, it.date(3), it.time(4), it.date(5), it.int(6), it.str(7), it.date(8), it.date(9)) },
            db.watch("SELECT id, name, sort_order FROM lists") { ListRow(it.str(0)!!, it.str(1)!!, it.int(2)) },
            db.watch(journalDaysSql) { it.date(0)!! },
            // Active day (spec 6.4): any habit tick, task completion or journal entry.
            db.watch(
                """SELECT loopdo_date FROM habit_logs WHERE count > 0
                   UNION SELECT completed_on FROM tasks WHERE completed_on IS NOT NULL
                   UNION $journalDaysSql""",
            ) { it.date(0)!! },
        )
        @Suppress("UNCHECKED_CAST")
        return combine(flows) { r ->
            TodayRows(
                versions = r[0] as List<DaySettingsVersion>,
                onboardedAt = (r[1] as List<Instant>).firstOrNull(),
                habits = r[2] as List<HabitRow>,
                schedules = r[3] as List<ScheduleRow>,
                logs = r[4] as List<LogRow>,
                tasks = r[5] as List<TaskRow>,
                lists = r[6] as List<ListRow>,
                journalDays = (r[7] as List<LocalDate>).toSet(),
                activeDays = (r[8] as List<LocalDate>).toSet(),
            )
        }
    }
}

/**
 * The today model at [now] and when that day ends, or null before the day settings have
 * synced. The today screen and the widget both build "today" through this one function.
 */
fun TodayRows.toModel(now: Instant): Pair<TodayModel, Instant>? {
    if (versions.isEmpty()) return null
    val dateAt = app.loopdo.shared.day.dateResolver(versions)
    val today = dateAt(now)
    val end = app.loopdo.shared.day.dayEndAt(versions, now)
    return buildToday(
        now = now, dateAt = dateAt, dayEndAt = end,
        since = onboardedAt?.let(dateAt)?.coerceAtMost(today) ?: today,
        habits = habits, schedules = schedules, logs = logs, tasks = tasks, lists = lists,
        journalDays = journalDays, activeDays = activeDays,
    ) to end
}
