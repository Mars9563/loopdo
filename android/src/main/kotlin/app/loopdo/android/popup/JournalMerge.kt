package app.loopdo.android.popup

import java.time.LocalDate

/**
 * The text to store when a journal draft that started from [base] is saved while the entry now
 * reads [current]. Owner, 2026-11-16: merge, never overwrite. Text added elsewhere in the meantime
 * (e.g. a [+ add] journal line) is kept, and the user's edit is replayed on top of it.
 */
fun mergeJournal(current: String, base: String, draft: String): String = when {
    current == base -> draft
    draft == base -> current
    // The user only added text after the old entry: add that on its own line after the newer text.
    draft.startsWith(base) -> current.trimEnd() + "\n" + draft.substring(base.length).trimStart('\n', ' ')
    // An edit that can't be replayed: keep both, the draft below a marker line.
    else -> current.trimEnd() + "\n\n[unsaved draft]\n" + draft
}

/** The journal pop-up opens on the latest earlier day that still has unsaved text, else on [today]. */
fun journalDateToOpen(today: LocalDate, draftDates: Set<LocalDate>): LocalDate =
    draftDates.filter { it < today }.maxOrNull() ?: today
