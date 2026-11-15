package app.loopdo.server

import app.loopdo.shared.day.DaySettings
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.day.nextVersion
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsAndPhotosTest {
    private val secret = "test-anchor-secret-32-bytes-long!!".toByteArray()
    private val now = Instant.parse("2026-11-16T22:30:00Z") // Tue 17 Nov 04:00 IST
    private val r2 = R2Config("acct123", "loopdo", "AKIDEXAMPLE", "secret-example")

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            loopdoModule(Deps(Db(TestDb.apiDataSource()), TestAuth.jwkProvider, TestAuth.ISSUER, secret,
                photos = PhotoUrls(r2)) { now })
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, user: UUID, body: JsonObject): HttpResponse =
        client.post(path) {
            bearerAuth(TestAuth.token(user)); contentType(ContentType.Application.Json); setBody(body.toString())
        }

    private fun settings(tz: String, end: String) = buildJsonObject { put("timeZone", tz); put("dayEnd", end) }
    private fun json(r: String) = Json.parseToJsonElement(r).jsonObject

    @Test
    fun firstSettingsCallCreatesBaseVersionThenLaterCallSchedulesPending() = app {
        val c = TestDb.userC
        val first = postJson("/api/day-settings", c, settings("Asia/Kolkata", "04:00"))
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(Instant.EPOCH.toString(), json(first.bodyAsText()).getValue("effectiveAt").jsonPrimitive.content)

        val second = postJson("/api/day-settings", c, settings("America/New_York", "04:00"))
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        val ist = DaySettings(ZoneId.of("Asia/Kolkata"), LocalTime.of(4, 0))
        val ny = DaySettings(ZoneId.of("America/New_York"), LocalTime.of(4, 0))
        val expected = nextVersion(listOf(DaySettingsVersion(Instant.EPOCH, ist, null)), ny, now)
        assertEquals(expected.effectiveAt.toString(), json(second.bodyAsText()).getValue("effectiveAt").jsonPrimitive.content)

        // A third request before that takes effect replaces the pending version.
        postJson("/api/day-settings", c, settings("Europe/London", "04:00"))
        assertEquals("2", TestDb.scalar("select count(*) from public.day_settings_versions where user_id = '$c'"))
    }

    @Test
    fun badSettingsRejected() = app {
        for (body in listOf(settings("Mars/Olympus_Mons", "04:00"), settings("posix/Asia/Kolkata", "04:00"),
                            settings("Asia/Kolkata", "09:00"), settings("Asia/Kolkata", "4am"))) {
            assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/api/day-settings", TestDb.userA, body).status, body.toString())
        }
    }

    private fun presign(entry: UUID, photo: UUID = UUID.randomUUID(), bytes: Int = 400_000, type: String = "image/webp") =
        buildJsonObject {
            put("photoId", photo.toString()); put("entryId", entry.toString()); put("contentType", type); put("bytes", bytes)
        }

    /** Creates a journal entry for [user] through the real upload endpoint. */
    private suspend fun ApplicationTestBuilder.entryFor(user: UUID, date: String): UUID {
        val id = UUID.randomUUID()
        val op = buildJsonObject {
            put("op", "PUT"); put("table", "journal_entries"); put("id", id.toString())
            put("data", buildJsonObject { put("loopdo_date", date); put("text", "hi") })
        }
        val r = postJson("/api/sync/upload", user, buildJsonObject { put("ops", buildJsonArray { add(op) }) })
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return id
    }

    @Test
    fun presignKeyIsUserScoped() = app {
        val a = TestDb.userA
        val entry = entryFor(a, "2026-10-01")
        val photo = UUID.randomUUID()
        val r = postJson("/api/photos/presign", a, presign(entry, photo))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = json(r.bodyAsText())
        assertEquals("u/$a/$entry/$photo.webp", body.getValue("key").jsonPrimitive.content)
        val url = Url(body.getValue("putUrl").jsonPrimitive.content)
        assertEquals("acct123.r2.cloudflarestorage.com", url.host)
        assertEquals("/loopdo/u/$a/$entry/$photo.webp", url.encodedPath)
        assertEquals("900", url.parameters["X-Amz-Expires"])
        assertNotNull(url.parameters["X-Amz-Signature"])
    }

    @Test
    fun presignOnlyForOwnEntry() = app {
        val entry = entryFor(TestDb.userA, "2026-10-02")
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/api/photos/presign", TestDb.userB, presign(entry)).status)
    }

    @Test
    fun presignRejectsLargeFileAndOtherTypes() = app {
        val entry = entryFor(TestDb.userA, "2026-10-03")
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/api/photos/presign", TestDb.userA, presign(entry, bytes = 6_000_000)).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/api/photos/presign", TestDb.userA, presign(entry, type = "image/gif")).status)
    }

    @Test
    fun getUrlOnlyForOwner() = app {
        val a = TestDb.userA
        val entry = entryFor(a, "2026-10-04")
        val photo = UUID.randomUUID()
        val op = buildJsonObject {
            put("op", "PUT"); put("table", "journal_photos"); put("id", photo.toString())
            put("data", buildJsonObject { put("entry_id", entry.toString()); put("r2_key", "u/$a/$entry/$photo.webp") })
        }
        assertEquals(HttpStatusCode.OK, postJson("/api/sync/upload", a, buildJsonObject { put("ops", buildJsonArray { add(op) }) }).status)

        val mine = client.get("/api/photos/$photo/url") { bearerAuth(TestAuth.token(a)) }
        assertEquals(HttpStatusCode.OK, mine.status)
        val url = Url(json(mine.bodyAsText()).getValue("getUrl").jsonPrimitive.content)
        assertTrue(url.encodedPath.endsWith("$photo.webp"))
        assertEquals("3600", url.parameters["X-Amz-Expires"])

        assertEquals(HttpStatusCode.NotFound, client.get("/api/photos/$photo/url") { bearerAuth(TestAuth.token(TestDb.userB)) }.status)
    }
}
