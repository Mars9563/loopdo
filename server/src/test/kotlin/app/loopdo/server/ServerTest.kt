package app.loopdo.server

import app.loopdo.shared.time.verifyAnchor
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {
    private val anchorSecret = "test-anchor-secret-32-bytes-long!!".toByteArray()

    private fun deps(db: Db = Db(TestDb.apiDataSource())) =
        Deps(db = db, jwkProvider = TestAuth.jwkProvider, issuer = TestAuth.ISSUER, anchorSecret = anchorSecret)

    private fun withApp(deps: Deps = deps(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { loopdoModule(deps) }
        block()
    }

    @Test
    fun healthIsOk() = withApp {
        val r = client.get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("ok", r.bodyAsText())
    }

    @Test
    fun timeRequiresAuth() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/time").status)
    }

    @Test
    fun timeReturnsSignedServerTime() = withApp {
        val r = client.get("/api/time") { bearerAuth(TestAuth.token(TestDb.userA)) }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        val serverTime = Instant.parse(body.getValue("serverTime").jsonPrimitive.content)
        val sig = body.getValue("sig").jsonPrimitive.content
        assertTrue(verifyAnchor(anchorSecret, TestDb.userA.toString(), serverTime, sig))
        assertNotNull(r.headers["X-Server-Time"])
    }

    @Test
    fun wrongIssuerRejected() = withApp {
        val t = TestAuth.token(TestDb.userA, issuer = "https://evil.example/auth/v1")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/time") { bearerAuth(t) }.status)
    }

    @Test
    fun expiredTokenRejected() = withApp {
        val t = TestAuth.token(TestDb.userA, expiresAt = Instant.now().minusSeconds(600))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/time") { bearerAuth(t) }.status)
    }

    @Test
    fun dbDownGives503() {
        val dead = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://127.0.0.1:1/postgres"
            username = "x"; password = "x"
            connectionTimeout = 1000
            initializationFailTimeout = -1
        })
        withApp(deps(Db(dead))) {
            val r = client.get("/api/time") { bearerAuth(TestAuth.token(TestDb.userA)) }
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        }
    }
}
