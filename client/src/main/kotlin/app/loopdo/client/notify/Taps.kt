package app.loopdo.client.notify

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// D6: what a widget tap or a notification button does to an item (moved from the phone's widget so the laptop's
// notifications use the same commands).

enum class TapResult { DONE, STALE, FAILED }

enum class TapKind(val path: String) { TICK("tick"), PLUS("plus"), UNTICK("untick"), TASK("task"), COMPLETE("complete") }

/** One tap target: what to do to which item, as shown on [date] ([done] = the row showed it done). */
data class WidgetTap(val kind: TapKind, val id: String, val date: LocalDate, val done: Boolean, val step: Int = 1)

/** [target] (a trusted instant) as a boot-clock time. */
fun toElapsed(trustedNow: Instant, elapsedNowMs: Long, target: Instant): Long =
    elapsedNowMs + Duration.between(trustedNow, target).toMillis()
