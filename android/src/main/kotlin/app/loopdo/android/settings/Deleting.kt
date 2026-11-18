package app.loopdo.android.settings

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** M9 (design §3.4): the account asked to be deleted; [canKeep] is false once the server started the wipe. */
data class Deleting(val deleteOn: LocalDate, val canKeep: Boolean) {
    val deleteOnLabel: String get() = deleteOn.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).lowercase()
}

/** Null = no deletion asked: the app shows as usual. */
fun deletingState(requestedAt: Instant?, claimedAt: Instant?, zone: ZoneId): Deleting? =
    requestedAt?.let { Deleting(it.plusSeconds(7 * 86_400L).atZone(zone).toLocalDate(), canKeep = claimedAt == null) }
