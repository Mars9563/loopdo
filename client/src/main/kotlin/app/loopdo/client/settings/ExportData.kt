package app.loopdo.client.settings

import app.loopdo.client.today.TodayData
import app.loopdo.shared.day.settingsAt
import com.powersync.PowerSyncDatabase
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Every synced table that goes into data.json (local-only queues and drafts don't). */
val EXPORT_TABLES = listOf(
    "profiles", "day_settings_versions", "habits", "habit_schedules", "habit_logs", "lists", "tasks", "subtasks",
    "journal_entries", "journal_lines", "journal_photos", "weekly_focus",
)

/** Reads this phone's copy of the account for the export (M9). Photos not on the phone are fetched with [photoFile]. */
class ExportData(private val db: PowerSyncDatabase, private val photoFile: suspend (id: String, localPath: String?) -> File?) {

    /** Every row as stored, per table (PowerSync keeps each row as JSON); deleted journal lines are left out. */
    suspend fun dataJson(): String {
        val parts = EXPORT_TABLES.map { t ->
            val where = if (t == "journal_lines") "WHERE json_extract(data, '$.deleted_at') IS NULL" else ""
            val rows = db.get("SELECT coalesce(json_group_array(json(json_patch(data, json_object('id', id)))), '[]') FROM ps_data__$t $where") { it.getString(0)!! }
            "\"$t\":$rows"
        }
        return parts.joinToString(",", "{", "}")
    }

    suspend fun days(): List<ExportDay> {
        val versions = TodayData(db).versionsNow()
        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        fun parse(s: String) = OffsetDateTime.parse(s.trim().replace(' ', 'T').replace(Regex("([+-]\\d{2})$"), "$1:00")).toInstant()
        val entries = db.getAll("SELECT id, loopdo_date, mood FROM journal_entries") { Triple(it.getString(0)!!, LocalDate.parse(it.getString(1)!!.take(10)), it.getLong(2)?.toInt()) }
        val lines = db.getAll(
            "SELECT entry_id, stamped_at, text FROM journal_lines WHERE deleted_at IS NULL AND trim(text) <> '' ORDER BY sort_order, stamped_at",
        ) { Triple(it.getString(0)!!, it.getString(1), it.getString(2)!!) }.groupBy { it.first }
        val photos = db.getAll("SELECT entry_id, id FROM journal_photos ORDER BY sort_order") { it.getString(0)!! to it.getString(1)!! }.groupBy({ it.first }, { it.second })
        return entries.mapNotNull { (id, date, mood) ->
            val dayLines = lines[id].orEmpty().map { (_, at, text) ->
                val time = at?.let { runCatching { parse(it) }.getOrNull() }?.let { t: Instant -> t.atZone(settingsAt(versions, t).zone).format(hhmm) } ?: ""
                time to text
            }
            val dayPhotos = photos[id].orEmpty()
            if (dayLines.isEmpty() && dayPhotos.isEmpty() && mood == null) null else ExportDay(date, mood, dayLines, dayPhotos)
        }.sortedBy { it.date }
    }

    /** Fetches what it can (offline: the missing photos are named in the result), then writes the ZIP to [out]. */
    suspend fun export(out: OutputStream, today: LocalDate): ExportResult {
        val days = days()
        val local = db.getAll("SELECT id, path FROM photo_files") { it.getString(0)!! to it.getString(1) }.toMap()
        val files = days.flatMap { it.photos }.associateWith { id -> runCatching { photoFile(id, local[id]) }.getOrNull() }
        return writeExport(out, days, { files[it] }, dataJson(), today)
    }
}
