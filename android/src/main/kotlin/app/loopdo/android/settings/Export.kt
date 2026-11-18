package app.loopdo.android.settings

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// M9 (design §3.2): the copy that depends on neither the phone nor the server. Built on the phone from local data;
// plain files anyone can open: one Markdown file per journal day, the photos, every row as JSON, and a README.

/** One journal day. [lines]: (HH:mm or "" for an untimed old line, text). */
data class ExportDay(val date: LocalDate, val mood: Int?, val lines: List<Pair<String, String>>, val photos: List<String>)

data class ExportResult(val days: Int, val photos: Int, val missing: List<String>, val bytes: Long)

private val heading = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

fun dayMarkdown(d: ExportDay): String = buildString {
    append("# ").append(d.date.format(heading).lowercase()).append("\n\n")
    d.mood?.let { append("mood: ").append(it).append("/5\n\n") }
    d.lines.forEach { (time, text) -> if (time.isEmpty()) append(text).append('\n') else append("[").append(time).append("] ").append(text).append('\n') }
    if (d.photos.isNotEmpty()) {
        append('\n')
        d.photos.forEach { append("![](../photos/").append(d.date).append('/').append(it).append(".webp)\n") }
    }
}

private class Counting(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
}

private fun plural(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"

/** Writes the ZIP to [out] (left open for the caller to close). [photo] gives a photo's file, or null if it isn't here. */
fun writeExport(out: OutputStream, days: List<ExportDay>, photo: (String) -> File?, dataJson: String, today: LocalDate): ExportResult {
    val counting = Counting(out)
    val missing = mutableListOf<String>()
    var photos = 0
    val zip = ZipOutputStream(counting)
    fun text(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() }
    days.sortedBy { it.date }.forEach { d ->
        text("journal/${d.date}.md", dayMarkdown(d))
        d.photos.forEach { id ->
            val f = photo(id)?.takeIf { it.exists() }
            if (f == null) missing += id else {
                zip.putNextEntry(ZipEntry("photos/${d.date}/$id.webp")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); photos++
            }
        }
    }
    text("data.json", dataJson)
    text("README.txt", buildString {
        append("loopdo_ export, made on ").append(today).append(".\n\n")
        append("journal/    one Markdown file per day: mood and every line with its time.\n")
        append("photos/     the journal's photos, one folder per day.\n")
        append("data.json   everything in your account (habits, ticks, tasks, lists, journal, focus, settings) as JSON.\n")
        if (missing.isNotEmpty()) append("\n").append(plural(missing.size, "photo")).append(if (missing.size == 1) " wasn't" else " weren't")
            .append(" on this phone (offline): ").append(missing.joinToString(", ")).append(".\n")
    })
    zip.finish()
    zip.flush()
    return ExportResult(days.size, photos, missing, counting.count)
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(Locale.ENGLISH, b / 1024.0)
    else -> "%.1f MB".format(Locale.ENGLISH, b / (1024.0 * 1024.0))
}

/** The line shown after an export (spec §3.2). */
fun exportResultLine(r: ExportResult): String =
    "saved: ${plural(r.days, "day")}, ${plural(r.photos, "photo")}, ${formatBytes(r.bytes)}" +
        if (r.missing.isEmpty()) "" else " · ${plural(r.missing.size, "photo")} ${if (r.missing.size == 1) "wasn't" else "weren't"} on this phone"
