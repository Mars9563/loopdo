package app.loopdo.android.journal

import app.loopdo.android.popup.PopupDrafts
import java.time.Instant
import java.time.LocalDate

/**
 * The unsaved journal line of a day, shared by the app's writer and the widget pop-up (one store, so typed text
 * survives closing either, a tab switch, the app lock and a restart). Keys as since M4: `journal:<date>` (the
 * text), `:base` (a pre-M6 whole-entry draft's base), `:at` (when the line was last edited on its own day).
 */
class LineDrafts(private val store: PopupDrafts) {
    private fun key(date: LocalDate) = "journal:$date"

    /** The day's unsaved line and when it was last edited (a pre-M6 draft: only the text typed after its base). */
    fun line(date: LocalDate): Pair<String, Instant?> {
        val text = store.load(key(date))
        val base = store.load(key(date) + ":base")
        val line = if (base.isEmpty()) text else (if (text.startsWith(base)) text.removePrefix(base) else text).trim()
        return line to store.load(key(date) + ":at").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }

    /**
     * Keeps [text]. Its time is [at] while the day is still today; an [earlierDay]'s draft keeps the time it was
     * last edited on its own day (review of M6: an edit the next morning must not move it out of its day).
     */
    fun keep(date: LocalDate, text: String, at: Instant, earlierDay: Boolean) {
        store.save(key(date), text)
        store.save(key(date) + ":base", "")
        // An earlier day's draft keeps its stored time (none for a pre-M6 draft: it is stamped at that day's end).
        if (text.isEmpty()) store.save(key(date) + ":at", "") else if (!earlierDay) store.save(key(date) + ":at", at.toString())
    }

    fun clear(date: LocalDate) {
        listOf("", ":base", ":at").forEach { store.save(key(date) + it, "") }
    }

    /** Days with an unsaved line. */
    fun dates(): Set<LocalDate> = store.keys()
        .filter { it.startsWith("journal:") && it.count { c -> c == ':' } == 1 && store.load(it).isNotEmpty() }
        .mapNotNull { runCatching { LocalDate.parse(it.removePrefix("journal:")) }.getOrNull() }.toSet()
}
