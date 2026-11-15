package app.loopdo.server

import app.loopdo.server.routes.timeRoutes
import com.auth0.jwk.JwkProviderBuilder
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

class Deps(
    val db: Db,
    val jwkProvider: com.auth0.jwk.JwkProvider,
    val issuer: String,
    val anchorSecret: ByteArray,
    val clock: () -> Instant = Instant::now,
)

@Serializable
data class ApiError(val code: String)

/** Every /api response tells the phone the server's time (keeps its anchor fresh). */
private fun serverTimeHeader(clock: () -> Instant) = createRouteScopedPlugin("ServerTimeHeader") {
    onCall { call -> call.response.header("X-Server-Time", clock().toString()) }
}

fun Application.loopdoModule(deps: Deps) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<DbUnavailable> { call, _ -> call.respond(HttpStatusCode.ServiceUnavailable, ApiError("UNAVAILABLE")) }
    }
    installAuth(deps.jwkProvider, deps.issuer)
    routing {
        get("/health") { call.respondText("ok") }
        route("/api") {
            install(serverTimeHeader(deps.clock))
            authenticate(AUTH) {
                timeRoutes(deps)
            }
        }
    }
}

fun main() {
    val config = Config.fromEnv()
    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.dbUrl
        username = config.dbUser
        password = config.dbPassword
        maximumPoolSize = 3 // free-tier pooler limits
        connectionTimeout = 5_000
        initializationFailTimeout = -1 // start even if the database is asleep; requests get 503
    })
    val jwks = JwkProviderBuilder(URI(config.jwksUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()
    val deps = Deps(Db(dataSource), jwks, config.issuer, config.anchorSecret)
    embeddedServer(Netty, port = config.port) { loopdoModule(deps) }.start(wait = true)
}
