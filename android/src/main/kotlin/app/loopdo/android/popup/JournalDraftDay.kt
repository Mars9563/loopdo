package app.loopdo.android.popup

import java.time.LocalDate

/** The journal pop-up opens on the latest earlier day that still has unsaved text, else on [today]. */
fun journalDateToOpen(today: LocalDate, draftDates: Set<LocalDate>): LocalDate =
    draftDates.filter { it < today }.maxOrNull() ?: today
