package app.loopdo.server.routes

import app.loopdo.server.Deps
import app.loopdo.server.UserPrincipal
import app.loopdo.shared.time.signAnchor
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class TimeResponse(val serverTime: String, val sig: String)

/**
 * The phone's time anchor. Also touches the database as the user, so the daily
 * keep-alive keeps Supabase awake and notices when it isn't.
 */
fun Route.timeRoutes(deps: Deps) {
    get("/time") {
        val user = call.principal<UserPrincipal>()!!
        deps.db.asUser(user.id) { c -> c.createStatement().use { it.execute("select 1") } }
        val now = deps.clock()
        call.respond(TimeResponse(now.toString(), signAnchor(deps.anchorSecret, user.id.toString(), now)))
    }
}
