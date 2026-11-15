package app.loopdo.server.routes

import app.loopdo.server.ApiError
import app.loopdo.server.Deps
import app.loopdo.server.UserPrincipal
import app.loopdo.server.loadDaySettingsVersions
import app.loopdo.shared.Limits
import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.day.nextVersion
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class DaySettingsRequest(val timeZone: String, val dayEnd: String)

@Serializable
data class DaySettingsScheduled(val effectiveAt: String, val floorDate: String?)

private val ZONES: Set<String> = ZoneId.getAvailableZoneIds().filterNot { it.startsWith("posix/") || it.startsWith("right/") }.toSet()

private fun parse(req: DaySettingsRequest): DaySettings? {
    if (req.timeZone !in ZONES) return null
    val end = runCatching { LocalTime.parse(req.dayEnd) }.getOrNull() ?: return null
    if (end > LocalTime.of(Limits.DAY_END_MAX_HOUR, 0)) return null
    return DaySettings(ZoneId.of(req.timeZone), end)
}

/**
 * Time zone and day end. The first call (onboarding) sets them from the start of
 * time; later calls schedule a change that waits for today to end (shared rules),
 * replacing any change still waiting.
 */
fun Route.settingsRoutes(deps: Deps) {
    post("/day-settings") {
        val user = call.principal<UserPrincipal>()!!
        val new = runCatching { call.receive<DaySettingsRequest>() }.getOrNull()?.let(::parse)
        if (new == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ApiError("BAD_SETTINGS"))
            return@post
        }
        val now = deps.clock()
        val version = deps.db.asUser(user.id) { c ->
            val existing = loadDaySettingsVersions(c)
            val v = if (existing.isEmpty()) DaySettingsVersion(Instant.EPOCH, new, null) else nextVersion(existing, new, now)
            c.prepareStatement("delete from public.day_settings_versions where effective_at > ?").use {
                it.setTimestamp(1, Timestamp.from(now)); it.executeUpdate()
            }
            c.prepareStatement(
                "insert into public.day_settings_versions (user_id, effective_at, time_zone, day_end, floor_date) values (?::uuid, ?, ?, ?, ?)",
            ).use {
                it.setString(1, user.id.toString())
                it.setTimestamp(2, Timestamp.from(v.effectiveAt))
                it.setString(3, v.settings.zone.id)
                it.setObject(4, v.settings.dayEnd)
                it.setObject(5, v.floorDate)
                it.executeUpdate()
            }
            v
        }
        call.respond(DaySettingsScheduled(version.effectiveAt.toString(), version.floorDate?.toString()))
    }
}
