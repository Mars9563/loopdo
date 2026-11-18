package app.loopdo.android.journal

import app.loopdo.android.popup.MOODS
import app.loopdo.android.today.short
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class EntryRow(val id: String, val date: LocalDate, val text: String, val mood: Int?)

/** A live journal line (journal_lines, not deleted). [stampedAt] null = no time (old text). */
data class LineRow(val id: String, val entryId: String, val stampedAt: Instant?, val text: String, val sortOrder: Int)

/** A photo row; [localPath] = this phone's own file (it took the photo), else null (download it). */
data class PhotoRow(val id: String, val entryId: String, val addedAt: Instant?, val localPath: String?)

/** One day of the time log, with what its alt day bar shows: `sat 14 nov 🙂 ……… today`. */
data class DayView(
    val date: LocalDate,
    val mood: Int?,
    val blocks: List<LogBlock>,
    val localPaths: Map<String, String?>,
    val label: String,
    val moodFace: String,
    /** "today" on today's bar, else "". */
    val dayTag: String,
    val entryExists: Boolean = false,
)

/** [today] = only today; [feed] = every journal day newest first; [onThisDay] = the same date in earlier years. */
data class JournalModel(
    val today: DayView, val feed: List<DayView>, val onThisDay: List<DayView>, val streak: Int,
    /** The wall time now in the LoopDo zone: the stamp a line saved now gets (the writer's `[22:41] · now`). */
    val now: LocalTime = LocalTime.MIDNIGHT,
)

fun buildJournal(
    entries: List<EntryRow>, photos: List<PhotoRow>, today: LocalDate, zone: ZoneId, dayStart: LocalTime, streak: Int,
    lines: List<LineRow> = emptyList(),
): JournalModel {
    val photosBy = photos.groupBy { it.entryId }
    // Save order: untimed first, then by time (two devices' lines interleave by when they were written).
    val linesBy = lines.sortedWith(compareBy<LineRow>({ it.stampedAt != null }, { it.stampedAt }, { it.sortOrder }, { it.id })).groupBy { it.entryId }
    fun dayLines(e: EntryRow?): List<DayLine> = e?.let {
        linesOfText(it.text) + linesBy[it.id].orEmpty().map { l -> DayLine(l.id, l.stampedAt?.atZone(zone)?.toLocalTime(), l.text) }
    }.orEmpty()
    fun view(date: LocalDate, e: EntryRow?): DayView {
        val mine = e?.let { photosBy[it.id] }.orEmpty()
        return DayView(
            date = date, mood = e?.mood,
            blocks = dayLog(dayLines(e), mine.map { it.id to it.addedAt?.atZone(zone)?.toLocalTime() }, dayStart),
            localPaths = mine.associate { it.id to it.localPath },
            label = date.short(), moodFace = e?.mood?.let { MOODS.getOrNull(it - 1) }.orEmpty(),
            dayTag = if (date == today) "today" else "", entryExists = e != null,
        )
    }
    // A journal day has any text or at least one photo (the streak rule); an emptied entry isn't shown.
    val days = entries.filter {
        it.date <= today && (it.text.isNotBlank() || linesBy[it.id].orEmpty().isNotEmpty() || photosBy[it.id].orEmpty().isNotEmpty())
    }
        .sortedByDescending { it.date }
    return JournalModel(
        today = view(today, entries.firstOrNull { it.date == today }),
        feed = days.map { view(it.date, it) },
        onThisDay = days.filter { it.date.year < today.year && it.date.monthValue == today.monthValue && it.date.dayOfMonth == today.dayOfMonth }
            .map { view(it.date, it) },
        streak = streak,
    )
}

/** A day takes another photo (≤ 4, the server's limit); checked before the camera or gallery opens. */
fun canAddPhoto(day: DayView): Boolean = day.localPaths.size < 4

/** A small least-recently-used map (decoded photos: only the ones near the screen stay in memory). */
class Lru<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    @Synchronized operator fun get(key: K): V? = map[key]
    @Synchronized operator fun set(key: K, value: V) { map[key] = value }
}
