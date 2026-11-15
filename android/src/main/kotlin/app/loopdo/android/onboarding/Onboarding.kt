package app.loopdo.android.onboarding

import app.loopdo.android.auth.AuthState
import app.loopdo.shared.Limits
import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.loopDoDate
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the app shows first (Review Focus 4: nothing tickable before the first sync). */
sealed interface StartState {
    data object Loading : StartState
    data object SignedOut : StartState
    data object SyncingFirstTime : StartState
    data object NeedsOnboarding : StartState
    data object Ready : StartState
}

fun startState(auth: AuthState, hasSynced: Boolean?, hasSettings: Boolean): StartState = when (auth) {
    AuthState.Loading -> StartState.Loading
    AuthState.SignedOut -> StartState.SignedOut
    is AuthState.SignedIn -> when {
        hasSettings -> StartState.Ready // settings already on the phone: usable, even offline
        hasSynced != true -> StartState.SyncingFirstTime
        else -> StartState.NeedsOnboarding
    }
}

/** POST /api/day-settings (needs a connection once). */
interface SettingsApi {
    suspend fun setDaySettings(timeZone: String, dayEnd: String)
}

/** Writes display_name + onboarded_at locally; uploaded through the normal queue. */
interface ProfileWriter {
    suspend fun finishOnboarding(displayName: String, at: Instant)
}

class Onboarding(private val api: SettingsApi, private val profile: ProfileWriter, private val clock: () -> Instant = Instant::now) {
    suspend fun submit(zone: ZoneId, dayEnd: LocalTime, name: String): Result<Unit> {
        if (dayEnd > LocalTime.of(Limits.DAY_END_MAX_HOUR, 0)) return Result.failure(IllegalArgumentException("the day can end at 08:00 at the latest."))
        if (name.isBlank()) return Result.failure(IllegalArgumentException("tell us what to call you."))
        return try {
            api.setDaySettings(zone.id, "%02d:%02d".format(dayEnd.hour, dayEnd.minute))
            profile.finishOnboarding(name.trim(), clock())
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(IOException("setup needs a connection once. connect and tap [start] again."))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    companion object {
        private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val hm = DateTimeFormatter.ofPattern("HH:mm")

        /** The live sentence under the day-end choice (locked setup mockup). */
        fun example(now: Instant, zone: ZoneId, dayEnd: LocalTime): String {
            val today = loopDoDate(now, DaySettings(zone, dayEnd)).format(day).lowercase()
            val clock = now.atZone(zone).toLocalTime().format(hm)
            if (dayEnd == LocalTime.MIDNIGHT) return "your day ends at midnight, like the calendar. right now ($clock) it is $today."
            val sample = if (dayEnd >= LocalTime.of(2, 0)) dayEnd.minusHours(2) else LocalTime.MIDNIGHT
            return "late nights count as the same day. right now ($clock) it is $today; at ${sample.format(hm)} tonight it will still be $today."
        }
    }
}

/** Real IANA zones (no posix/ or right/ duplicates), sorted; search ignores case, '_' and '/'. */
object Zones {
    fun all(): List<String> = ZoneId.getAvailableZoneIds()
        .filter { it.contains('/') && !it.startsWith("posix/") && !it.startsWith("right/") && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
        .sorted()

    private fun norm(s: String) = s.lowercase().replace('_', ' ').replace('/', ' ')

    fun search(q: String): List<String> = all().filter { norm(it).contains(norm(q.trim())) }
}
