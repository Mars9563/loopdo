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
import java.sql.SQLException
import java.time.ZoneOffset

@Serializable data class DeletionScheduled(val requestedAt: String, val deleteOn: String)
@Serializable data class Signups(val enabled: Boolean)

/** M9: delete with a 7-day undo (the database functions of migration 0011 do the work), and the admin switch. */
fun Route.accountRoutes(deps: Deps) {
    post("/account/delete") {
        val user = call.principal<UserPrincipal>()!!
        val at = try {
            deps.db.asUser(user.id) { c -> c.prepareStatement("select public.request_account_deletion()").use { st -> st.executeQuery().use { it.next(); it.getObject(1, java.time.OffsetDateTime::class.java) } } }
        } catch (e: SQLException) {
            if ("WIPE_STARTED" in (e.message ?: "")) return@post call.respond(HttpStatusCode.Conflict, ApiError("WIPE_STARTED")) else throw e
        }
        call.respond(DeletionScheduled(at.toInstant().toString(), at.toInstant().plusSeconds(7 * 86_400L).atOffset(ZoneOffset.UTC).toLocalDate().toString()))
    }
    post("/account/keep") {
        val user = call.principal<UserPrincipal>()!!
        try {
            deps.db.asUser(user.id) { c -> c.prepareStatement("select public.keep_account()").use { it.execute() } }
        } catch (e: SQLException) {
            if ("WIPE_STARTED" in (e.message ?: "")) return@post call.respond(HttpStatusCode.Conflict, ApiError("WIPE_STARTED")) else throw e
        }
        call.respond(HttpStatusCode.OK, mapOf("kept" to true))
    }
    get("/admin/signups") {
        val user = call.principal<UserPrincipal>()!!
        val enabled = deps.db.asUser(user.id) { c ->
            if (!isAdmin(c)) null else c.prepareStatement("select signups_enabled from public.app_config where id = 1").use { st -> st.executeQuery().use { it.next(); it.getBoolean(1) } }
        } ?: return@get call.respond(HttpStatusCode.Forbidden, ApiError("NOT_ADMIN"))
        call.respond(Signups(enabled))
    }
    post("/admin/signups") {
        val user = call.principal<UserPrincipal>()!!
        val req = runCatching { call.receive<Signups>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, ApiError("BAD_REQUEST"))
        val ok = deps.db.asUser(user.id) { c ->
            if (!isAdmin(c)) false else {
                c.prepareStatement("update public.app_config set signups_enabled = ? where id = 1").use { it.setBoolean(1, req.enabled); it.executeUpdate() }
                true
            }
        }
        if (ok) call.respond(req) else call.respond(HttpStatusCode.Forbidden, ApiError("NOT_ADMIN"))
    }
}

private fun isAdmin(c: java.sql.Connection): Boolean =
    c.prepareStatement("select coalesce((select is_admin from public.profiles where id = public.current_user_id()), false)").use { st ->
        st.executeQuery().use { it.next(); it.getBoolean(1) }
    }
