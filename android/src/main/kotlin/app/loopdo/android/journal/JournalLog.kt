package app.loopdo.android.journal

import java.time.LocalTime
import java.util.Locale

// The journal as a time log (M6, owner's idea; locked journal-log-v2). M6.5: each saved line is its own row
// (journal_lines) with its time, so two devices never overwrite each other's lines. Text saved before M6.5
// (journal_entries.text, one "[HH:mm] " line per text line) is moved into rows once; until then it shows as is.
// Photos are rows with their own time (journal_photos.added_at).

data class LogLine(val time: LocalTime?, val text: String)

/** A saved line of a day: [id] = its journal_lines row ("text:<n>" for old text not moved yet). */
data class DayLine(val id: String, val time: LocalTime?, val text: String)

sealed interface LogItem {
    /** [raw] = the line as shown with its stamp (dialogs); [id] = what an edit or delete names. */
    data class Line(val index: Int, val line: LogLine, val raw: String = "", val id: String = "") : LogItem
    data class Photo(val id: String, val time: LocalTime?) : LogItem
}

/** Items close together in time; a separator is drawn between blocks. */
data class LogBlock(val items: List<LogItem>)

/** Items at least this far apart start a new block (owner, 2026-11-17). */
const val GAP_MINUTES = 30

private val STAMP = Regex("""^\[(\d{2}):(\d{2})] ?(.*)$""")

private fun stampOf(t: LocalTime) = String.format(Locale.ROOT, "[%02d:%02d]", t.hour, t.minute)

private fun parse(raw: String): LogLine = STAMP.matchEntire(raw)?.let { m ->
    val (h, min, text) = m.destructured
    runCatching { LogLine(LocalTime.of(h.toInt(), min.toInt()), text) }.getOrNull()
} ?: LogLine(null, raw)

private fun storedLines(text: String): List<String> = text.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }

/** Old day text's lines in save order; a line without a stamp (written before M6) has no time. */
fun parseLines(text: String): List<LogLine> = storedLines(text).map(::parse)

/** Old day text as lines (not moved into rows yet). */
fun linesOfText(text: String): List<DayLine> = parseLines(text).mapIndexed { i, l -> DayLine("text:$i", l.time, l.text) }

/** What the writer saves: each non-blank line of [input], trimmed; each becomes one row. */
fun splitInput(input: String): List<String> = input.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/** How a line is shown: `[09:05] text`, or just the text when it has no time. */
fun shown(time: LocalTime?, text: String): String = if (time == null) text else "${stampOf(time)} $text"

/**
 * The day as blocks: untimed (old) lines first, then the timed lines in save order with each photo after the
 * last line whose time is not later than its own (a photo without a time goes last). Times are compared from
 * [dayStart] (the LoopDo day end), so 00:10 falls between 23:50 and 01:30. Items [GAP_MINUTES] or more apart
 * start a new block.
 */
fun dayLog(text: String, photos: List<Pair<String, LocalTime?>>, dayStart: LocalTime = LocalTime.of(4, 0)): List<LogBlock> =
    dayLog(linesOfText(text), photos, dayStart)

/** The day as blocks, from its saved lines (in save order) and photos; see the text form above. */
fun dayLog(dayLines: List<DayLine>, photos: List<Pair<String, LocalTime?>>, dayStart: LocalTime = LocalTime.of(4, 0)): List<LogBlock> {
    fun key(t: LocalTime) = Math.floorMod(t.toSecondOfDay() / 60 - dayStart.toSecondOfDay() / 60, 24 * 60)
    val lines = dayLines.mapIndexed { i, l -> LogItem.Line(i, LogLine(l.time, l.text), shown(l.time, l.text), l.id) }
    val untimed = lines.filter { it.line.time == null }
    val timed = lines.filter { it.line.time != null }
    val timedPhotos = photos.filter { it.second != null }.map { LogItem.Photo(it.first, it.second) }.sortedBy { key(it.time!!) }

    // Merge: lines keep save order; a photo goes after the last line at or before its time.
    val merged = mutableListOf<LogItem>()
    val pending = timedPhotos.toMutableList()
    timed.forEachIndexed { i, line ->
        val k = key(line.line.time!!)
        // Photos earlier than this line (and not later than any line before it) go before it.
        if (i == 0) pending.removeAll { p -> (key(p.time!!) < k).also { if (it) merged += p } }
        merged += line
        val nextKey = timed.getOrNull(i + 1)?.let { key(it.line.time!!) } ?: Int.MAX_VALUE
        pending.removeAll { p -> key(p.time!!).let { pk -> pk >= k && (pk < nextKey || nextKey < k) }.also { if (it) merged += p } }
    }
    merged += pending // photos with no line around them (a day of photos only)

    val blocks = mutableListOf<LogBlock>()
    if (untimed.isNotEmpty()) blocks += LogBlock(untimed)
    var current = mutableListOf<LogItem>()
    var last: Int? = null
    for (item in merged) {
        val t = when (item) { is LogItem.Line -> item.line.time!!; is LogItem.Photo -> item.time!! }
        val k = key(t)
        if (last != null && k - last >= GAP_MINUTES && current.isNotEmpty()) { blocks += LogBlock(current); current = mutableListOf() }
        current += item
        last = maxOf(last ?: k, k)
    }
    if (current.isNotEmpty()) blocks += LogBlock(current)
    val untimedPhotos = photos.filter { it.second == null }.map { LogItem.Photo(it.first, null) }
    if (untimedPhotos.isNotEmpty()) {
        if (blocks.isEmpty()) blocks += LogBlock(untimedPhotos)
        else blocks[blocks.lastIndex] = LogBlock(blocks.last().items + untimedPhotos)
    }
    return blocks
}
