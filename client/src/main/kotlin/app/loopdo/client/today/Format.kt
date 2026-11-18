package app.loopdo.client.today

import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val lateFmt = DateTimeFormatter.ofPattern("dMMM", Locale.ENGLISH)

/** `sat 14 nov` (the app's and widget's date style). */
fun LocalDate.short(): String = format(dayFmt).lowercase(Locale.ENGLISH)

/** `13nov`, for the widget's `!late 13nov`. */
fun LocalDate.compact(): String = format(lateFmt).lowercase(Locale.ENGLISH)

/** `3h18m`, or `42m` in the last hour. */
fun Duration.countdown(): String = toMinutes().let { if (it >= 60) "${it / 60}h${it % 60}m" else "${it}m" }
