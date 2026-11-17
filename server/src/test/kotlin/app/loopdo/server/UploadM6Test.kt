package app.loopdo.server

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
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** M6 server rules (design 2026-11-18): the journal as a time log, photos placed at their time. */
class UploadM6Test {
    private val secret = "test-anchor-secret-32-bytes-long!!".toByteArray()
    private val monMorning = Instant.parse("2026-11-16T03:30:00Z") // Mon 09:00 IST
    private val a = TestDb.userA

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { loopdoModule(Deps(Db(TestDb.apiDataSource()), TestAuth.jwkProvider, TestAuth.ISSUER, secret) { monMorning }) }
        block()
    }

    private fun op(op: String, table: String, id: UUID, data: JsonObject? = null) = buildJsonObject {
        put("op", op); put("table", table); put("id", id.toString())
        if (data != null) put("data", data)
    }

    private suspend fun ApplicationTestBuilder.upload(user: UUID, vararg ops: JsonObject): HttpResponse =
        client.post("/api/sync/upload") {
            bearerAuth(TestAuth.token(user))
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("ops", buildJsonArray { ops.forEach { add(it) } }) }.toString())
        }

    @Test
    fun photoAddedAtIsStored() = app {
        val entry = UUID.randomUUID(); val photo = UUID.randomUUID()
        val r = upload(a,
            op("PUT", "journal_entries", entry, buildJsonObject { put("loopdo_date", "2026-11-16"); put("text", "[11:00] so i heard something funny today.") }),
            op("PUT", "journal_photos", photo, buildJsonObject {
                put("entry_id", entry.toString()); put("r2_key", "u/$a/$entry/$photo.webp")
                put("width", 2048); put("height", 1536); put("bytes", 412_000); put("sort_order", 0)
                put("added_at", "2026-11-16T05:30:00Z")
            }))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("2026-11-16 05:30:00+00", TestDb.scalar("select added_at at time zone 'UTC' || '+00' from public.journal_photos where id = '$photo'").let { it.substring(0, 19) + "+00" })
    }
}
