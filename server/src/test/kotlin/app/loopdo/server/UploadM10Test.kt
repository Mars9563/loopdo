package app.loopdo.server

import app.loopdo.shared.remind.presenceId
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D7 (M10 design): a laptop's "in use" signal — its own row only, never more than 10 min ahead. */
class UploadM10Test {
    private val secret = "test-anchor-secret-32-bytes-long!!".toByteArray()
    private val now = Instant.parse("2026-11-19T07:00:00Z")
    private val a = TestDb.userA
    private val b = TestDb.userB
    private val device = UUID.fromString("0d000000-0000-4000-8000-0000000000d7")

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        TestDb.admin("delete from public.device_presence") // tests share the database; each starts clean
        application { loopdoModule(Deps(Db(TestDb.apiDataSource()), TestAuth.jwkProvider, TestAuth.ISSUER, secret) { now }) }
        block()
    }

    private fun op(op: String, id: UUID, data: JsonObject) = buildJsonObject {
        put("op", op); put("table", "device_presence"); put("id", id.toString()); put("data", data); put("key", "k-${UUID.randomUUID()}")
    }

    private suspend fun ApplicationTestBuilder.upload(user: UUID, vararg ops: JsonObject): HttpResponse =
        client.post("/api/sync/upload") {
            bearerAuth(TestAuth.token(user))
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("ops", buildJsonArray { ops.forEach { add(it) } }) }.toString())
        }

    private fun stored(id: UUID): Instant? =
        TestDb.scalar("select to_char(active_until at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') from public.device_presence where id = '$id'")
            .takeIf { it.isNotBlank() }?.let(Instant::parse)

    private fun put(id: UUID, until: Instant) = op("PUT", id, buildJsonObject { put("device_id", device.toString()); put("active_until", until.toString()) })

    @Test
    fun `a laptop's presence is stored with its id derived from the device`() = app {
        val id = presenceId(a, device)
        val r = upload(a, put(id, now.plusSeconds(300)))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(now.plusSeconds(300), stored(id))
    }

    @Test
    fun `active_until is clamped to ten minutes ahead`() = app {
        val id = presenceId(a, device)
        assertEquals(HttpStatusCode.OK, upload(a, put(id, now.plus(Duration.ofHours(1)))).status)
        assertEquals(now.plus(Duration.ofMinutes(10)), stored(id), "a fast laptop clock can't silence the phone for an hour")
    }

    @Test
    fun `a presence id that isn't the device's is refused`() = app {
        val r = upload(a, put(UUID.randomUUID(), now.plusSeconds(300)))
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status, r.bodyAsText())
        assertTrue("BAD_OP" in r.bodyAsText(), r.bodyAsText())
        val other = upload(a, put(presenceId(b, device), now.plusSeconds(300)))
        assertEquals(HttpStatusCode.UnprocessableEntity, other.status, "another user's id: ${other.bodyAsText()}")
    }

    @Test
    fun `a PATCH moves active_until back to now and is clamped too`() = app {
        val id = presenceId(a, device)
        upload(a, put(id, now.plusSeconds(300)))
        assertEquals(HttpStatusCode.OK, upload(a, op("PATCH", id, buildJsonObject { put("active_until", now.toString()) })).status)
        assertEquals(now, stored(id))
        upload(a, op("PATCH", id, buildJsonObject { put("active_until", now.plus(Duration.ofDays(1)).toString()) }))
        assertEquals(now.plus(Duration.ofMinutes(10)), stored(id))
    }

    @Test
    fun `someone else's presence row can't be touched`() = app {
        val id = presenceId(a, device)
        upload(a, put(id, now.plusSeconds(300)))
        upload(b, op("PATCH", id, buildJsonObject { put("active_until", now.plus(Duration.ofMinutes(9)).toString()) }))
        assertEquals(now.plusSeconds(300), stored(id), "A's row unchanged")
    }
}
