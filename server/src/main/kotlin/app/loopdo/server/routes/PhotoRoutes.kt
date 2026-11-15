package app.loopdo.server.routes

import app.loopdo.server.ApiError
import app.loopdo.server.Deps
import app.loopdo.server.UserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PresignRequest(val photoId: String, val entryId: String, val contentType: String, val bytes: Long)

@Serializable
data class PresignResponse(val key: String, val putUrl: String)

@Serializable
data class GetUrlResponse(val getUrl: String)

const val MAX_PHOTO_BYTES = 5L * 1024 * 1024
private val TYPES = mapOf("image/webp" to "webp", "image/jpeg" to "jpg")

fun Route.photoRoutes(deps: Deps) {
    post("/photos/presign") {
        val user = call.principal<UserPrincipal>()!!
        val photos = deps.photos ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiError("UNAVAILABLE"))
        val req = runCatching { call.receive<PresignRequest>() }.getOrNull()
        val ext = req?.let { TYPES[it.contentType] }
        val photoId = req?.let { runCatching { UUID.fromString(it.photoId) }.getOrNull() }
        val entryId = req?.let { runCatching { UUID.fromString(it.entryId) }.getOrNull() }
        if (req == null || ext == null || photoId == null || entryId == null || req.bytes !in 1..MAX_PHOTO_BYTES) {
            return@post call.respond(HttpStatusCode.UnprocessableEntity, ApiError("BAD_PHOTO"))
        }
        val ownsEntry = deps.db.asUser(user.id) { c ->
            c.prepareStatement("select 1 from public.journal_entries where id = ?::uuid").use {
                it.setString(1, entryId.toString()); it.executeQuery().use { rs -> rs.next() }
            }
        }
        if (!ownsEntry) return@post call.respond(HttpStatusCode.UnprocessableEntity, ApiError("FORBIDDEN"))
        val key = "u/${user.id}/$entryId/$photoId.$ext"
        call.respond(PresignResponse(key, photos.put(key, req.contentType, req.bytes)))
    }

    get("/photos/{photoId}/url") {
        val user = call.principal<UserPrincipal>()!!
        val photos = deps.photos ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ApiError("UNAVAILABLE"))
        val photoId = runCatching { UUID.fromString(call.parameters["photoId"]) }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND"))
        val key = deps.db.asUser(user.id) { c ->
            c.prepareStatement("select r2_key from public.journal_photos where id = ?::uuid").use {
                it.setString(1, photoId.toString()); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        } ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND"))
        call.respond(GetUrlResponse(photos.get(key)))
    }
}
