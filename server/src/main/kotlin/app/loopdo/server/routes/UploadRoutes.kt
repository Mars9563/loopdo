package app.loopdo.server.routes

import app.loopdo.server.Deps
import app.loopdo.server.UserPrincipal
import app.loopdo.server.upload.Applier
import app.loopdo.server.upload.CrudOp
import app.loopdo.server.upload.Rejected
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class UploadRequest(val ops: List<CrudOp>)

@Serializable
data class UploadApplied(val applied: Int)

@Serializable
data class UploadRejected(val code: String, val opIndex: Int)

const val MAX_OPS_PER_BATCH = 500

/**
 * PowerSync's upload target. The whole batch is one transaction: all ops apply or
 * none do. 422 = permanent (the phone undoes the batch); 503 = try again later.
 */
fun Route.uploadRoutes(deps: Deps) {
    post("/sync/upload") {
        val user = call.principal<UserPrincipal>()!!
        val req = runCatching { call.receive<UploadRequest>() }.getOrNull()
        if (req == null || req.ops.size > MAX_OPS_PER_BATCH) {
            call.respond(HttpStatusCode.UnprocessableEntity, UploadRejected("BAD_OP", -1))
            return@post
        }
        try {
            val now = deps.clock()
            deps.db.asUser(user.id) { c ->
                val applier = Applier(c, user.id, deps.anchorSecret, now, deps.previousAnchorSecret)
                req.ops.forEachIndexed(applier::apply)
            }
            call.respond(UploadApplied(req.ops.size))
        } catch (r: Rejected) {
            call.respond(HttpStatusCode.UnprocessableEntity, UploadRejected(r.code, r.opIndex))
        }
    }
}
