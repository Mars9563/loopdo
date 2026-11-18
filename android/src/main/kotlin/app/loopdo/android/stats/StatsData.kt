package app.loopdo.android.stats

import app.loopdo.android.today.JOURNAL_DAYS_SQL
import app.loopdo.android.today.TodayData
import app.loopdo.android.today.parseRecurrence
import app.loopdo.android.today.parseWeekdays
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.habit.Habit
import app.loopdo.shared.habit.HabitKind
import app.loopdo.shared.habit.HabitLog
import app.loopdo.shared.habit.HabitSchedule
import app.loopdo.shared.stats.Focus
import app.loopdo.shared.stats.FocusKind
import app.loopdo.shared.stats.StatHabit
import app.loopdo.shared.stats.StatTask
import app.loopdo.shared.stats.WeekInput
import com.powersync.PowerSyncDatabase
import com.powersync.db.SqlCursor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** Everything the stats tab and the review read (M8). No journal text: moods and "has a journal" only. */
data class StatsRows(
    val versions: List<DaySettingsVersion>,
    val input: WeekInput,
    val moods: Map<LocalDate, Int>,
    val journalDays: Set<LocalDate>,
    val focus: List<Focus>,
)

private fun SqlCursor.date(i: Int) = getString(i)?.let { LocalDate.parse(it.take(10)) }

class StatsData(private val db: PowerSyncDatabase) {
    private class HabitRow(val id: String, val title: String, val count: Boolean, val target: Int, val archivedOn: LocalDate?, val order: Int)

    fun rows(): Flow<StatsRows> {
        val flows: List<Flow<List<Any>>> = listOf(
            TodayData(db).versionsFlow(),
            // Deleted-forever habits are hidden everywhere (their history still counts for the day streaks).
            db.watch("SELECT id, title, kind, target, archived_on, sort_order FROM habits WHERE deleted_at IS NULL") {
                HabitRow(it.getString(0)!!, it.getString(1)!!, it.getString(2) == "count", (it.getLong(3)?.toInt() ?: 1).coerceAtLeast(1), it.date(4), it.getLong(5)?.toInt() ?: 0)
            },
            db.watch("SELECT habit_id, valid_from, recurrence, weekdays, target FROM habit_schedules") {
                it.getString(0)!! to HabitSchedule(it.date(1)!!, parseRecurrence(it.getString(2)), parseWeekdays(it.getString(3)), it.getLong(4)?.toInt())
            },
            db.watch("SELECT habit_id, loopdo_date, count, verified FROM habit_logs") {
                it.getString(0)!! to HabitLog(it.date(1)!!, it.getLong(2)?.toInt() ?: 0, it.getLong(3) != 0L)
            },
            // A repeating task's template is not a task of its own.
            db.watch("SELECT id, title, due_date, completed_on, late_since FROM tasks WHERE repeat_rule IS NULL") {
                StatTask(it.getString(0)!!, it.getString(1)!!, it.date(2), it.date(3), it.date(4))
            },
            db.watch(
                """SELECT loopdo_date FROM habit_logs WHERE count > 0
                   UNION SELECT completed_on FROM tasks WHERE completed_on IS NOT NULL
                   UNION $JOURNAL_DAYS_SQL""",
            ) { it.date(0)!! },
            db.watch("SELECT loopdo_date, mood FROM journal_entries WHERE mood IS NOT NULL") { it.date(0)!! to it.getLong(1)!!.toInt() },
            db.watch(JOURNAL_DAYS_SQL) { it.date(0)!! },
            db.watch("SELECT id, week_start, kind, item_id, title, goal, deadline FROM weekly_focus WHERE deleted_at IS NULL ORDER BY week_start, sort_order") {
                Focus(
                    it.getString(0)!!, it.date(1)!!, if (it.getString(2) == "task") FocusKind.TASK else FocusKind.HABIT,
                    it.getString(3)!!, it.getString(4)!!, it.getLong(5)?.toInt(), it.date(6),
                )
            },
        )
        @Suppress("UNCHECKED_CAST")
        return combine(flows) { r ->
            val schedules = (r[2] as List<Pair<String, HabitSchedule>>).groupBy({ it.first }, { it.second })
            val habits = (r[1] as List<HabitRow>).map { h ->
                StatHabit(Habit(h.id, if (h.count) HabitKind.COUNT else HabitKind.YES_NO, h.target, schedules[h.id].orEmpty(), h.archivedOn), h.title, h.order)
            }
            StatsRows(
                versions = r[0] as List<DaySettingsVersion>,
                input = WeekInput(
                    habits, (r[3] as List<Pair<String, HabitLog>>).groupBy({ it.first }, { it.second }),
                    r[4] as List<StatTask>, (r[5] as List<LocalDate>).toSet(),
                ),
                moods = (r[6] as List<Pair<LocalDate, Int>>).toMap(),
                journalDays = (r[7] as List<LocalDate>).toSet(),
                focus = r[8] as List<Focus>,
            )
        }
    }
}
