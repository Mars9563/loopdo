package app.loopdo.client.journal

import java.time.LocalDate
import java.time.YearMonth

/** One day of the journal [calendar]: written or not, its mood, today, or still to come. */
data class CalDay(val date: LocalDate, val hasEntry: Boolean, val mood: Int?, val today: Boolean, val future: Boolean)

/** How strongly a day's cell is filled with the accent: brighter for a better mood; 0 = no mood (outline). */
fun moodShade(mood: Int?): Float = if (mood == null) 0f else 0.15f + (mood.coerceIn(1, 5) - 1) * 0.175f

/** The calendar starts at the month of the first entry (nothing earlier to show). */
fun canGoBack(month: YearMonth, firstEntry: LocalDate?): Boolean = firstEntry != null && month > YearMonth.from(firstEntry)

/** ...and ends at this month. */
fun canGoForward(month: YearMonth, today: LocalDate): Boolean = month < YearMonth.from(today)

/** [month]'s days; [days] = the journal days (any text or photo) with their mood. */
fun journalMonth(days: Map<LocalDate, Int?>, month: YearMonth, today: LocalDate): List<CalDay> =
    (1..month.lengthOfMonth()).map { month.atDay(it) }.map { d ->
        CalDay(d, d in days, days[d], d == today, d > today)
    }
