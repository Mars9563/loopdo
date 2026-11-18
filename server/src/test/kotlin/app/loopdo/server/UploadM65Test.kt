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

/** M6.5 (design 2026-11-18): two devices of one person; the latest change wins, never at the cost of a line. */
class UploadM65Test {
    private val secret = "test-anchor-secret-32-bytes-long!!".toByteArray()
    private val monMorning = Instant.parse("2026-11-16T03:30:00Z") // Mon 09:00 IST
    private val a = TestDb.userA
    private val b = TestDb.userB

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { loopdoModule(Deps(Db(TestDb.apiDataSource()), TestAuth.jwkProvider, TestAuth.ISSUER, secret) { monMorning }) }
        block()
    }

    private fun op(op: String, table: String, id: UUID, data: JsonObject? = null, old: JsonObject? = null) = buildJsonObject {
        put("op", op); put("table", table); put("id", id.toString())
        if (data != null) put("data", data)
        if (old != null) put("old", old)
    }

    private suspend fun ApplicationTestBuilder.upload(user: UUID, vararg ops: JsonObject): HttpResponse =
        client.post("/api/sync/upload") {
            bearerAuth(TestAuth.token(user))
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("ops", buildJsonArray { ops.forEach { add(it) } }) }.toString())
        }

    private suspend fun ApplicationTestBuilder.ok(user: UUID, vararg ops: JsonObject) {
        val r = upload(user, *ops)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    /** A day row for user A on a date of its own (the unique (user, date) key), and its id. */
    private suspend fun ApplicationTestBuilder.day(): UUID {
        val id = UUID.randomUUID()
        val date = java.time.LocalDate.of(2020, 1, 1).plusDays((Math.random() * 2000).toLong())
        ok(a, op("PUT", "journal_entries", id, buildJsonObject { put("loopdo_date", date.toString()); put("text", "") }))
        return id
    }

    private fun line(entry: UUID, text: String, editedAt: String, stampedAt: String = "2026-11-16T04:30:00Z") = buildJsonObject {
        put("entry_id", entry.toString()); put("stamped_at", stampedAt); put("text", text)
        put("edited_at", editedAt); put("sort_order", 0)
    }

    private fun text(id: UUID) = TestDb.scalar("select text from public.journal_lines where id = '$id'")
    private fun deleted(id: UUID) = TestDb.scalar("select (deleted_at is not null)::text from public.journal_lines where id = '$id'")

    @Test
    fun lineAddedOnTwoDevicesKeepsBoth() = app {
        val e = day(); val l1 = UUID.randomUUID(); val l2 = UUID.randomUUID()
        ok(a, op("PUT", "journal_lines", l1, line(e, "from the phone", "2026-11-16T04:30:00Z")))
        ok(a, op("PUT", "journal_lines", l2, line(e, "from the laptop", "2026-11-16T04:30:01Z")))
        assertEquals("2", TestDb.scalar("select count(*) from public.journal_lines where entry_id = '$e'"))
    }

    @Test
    fun newerEditWins() = app {
        val e = day(); val l = UUID.randomUUID()
        ok(a, op("PUT", "journal_lines", l, line(e, "first", "2026-11-16T04:30:00Z")))
        ok(a, op("PATCH", "journal_lines", l, buildJsonObject { put("text", "second"); put("edited_at", "2026-11-16T04:35:00Z") }))
        assertEquals("second", text(l))
    }

    @Test
    fun olderEditArrivingLaterIsSkipped() = app {
        val e = day(); val l = UUID.randomUUID()
        ok(a, op("PUT", "journal_lines", l, line(e, "first", "2026-11-16T04:30:00Z")))
        ok(a, op("PATCH", "journal_lines", l, buildJsonObject { put("text", "laptop, newer"); put("edited_at", "2026-11-16T04:35:00Z") }))
        ok(a, op("PATCH", "journal_lines", l, buildJsonObject { put("text", "phone, older"); put("edited_at", "2026-11-16T04:31:00Z") }))
        assertEquals("laptop, newer", text(l))
    }

    @Test
    fun deleteOfTheSeenVersionApplies() = app {
        val e = day(); val l = UUID.randomUUID()
        ok(a, op("PUT", "journal_lines", l, line(e, "bye", "2026-11-16T04:30:00Z")))
        ok(a, op("PATCH", "journal_lines", l, buildJsonObject { put("deleted_at", "2026-11-16T04:40:00Z") },
            old = buildJsonObject { put("edited_at", "2026-11-16T04:30:00Z") }))
        assertEquals("true", deleted(l))
    }

    @Test
    fun staleDeleteIsSkipped() = app {
        val e = day(); val l = UUID.randomUUID()
        ok(a, op("PUT", "journal_lines", l, line(e, "first", "2026-11-16T04:30:00Z")))
        ok(a, op("PATCH", "journal_lines", l, buildJsonObject { put("text", "edited on the laptop"); put("edited_at", "2026-11-16T04:35:00Z") }))
        // The phone deleted the line it saw (edited 04:30), without seeing the laptop's edit: the edited line is kept.
        ok(a, op("PATCH", "journal_lines", l, buildJsonObject { put("deleted_at", "2026-11-16T04:40:00Z") },
            old = buildJsonObject { put("edited_at", "2026-11-16T04:30:00Z") }))
        assertEquals("false", deleted(l))
        assertEquals("edited on the laptop", text(l))
    }

    @Test
    fun editRevivesADeletedLine() = app {
        val e = day(); val l = UUID.randomUUID()
        ok(a, op("PUT", "journal_lines", l, line(e, "first", "2026-11-16T04:30:00Z")))
        ok(a, op("PATCH", "journal_lines", l, buildJsonObject { put("deleted_at", "2026-11-16T04:40:00Z") },
            old = buildJsonObject { put("edited_at", "2026-11-16T04:30:00Z") }))
        // The laptop, offline, edited the line before it saw the delete: the edited line comes back.
        ok(a, op("PATCH", "journal_lines", l, buildJsonObject { put("text", "kept"); put("edited_at", "2026-11-16T04:35:00Z") },
            old = buildJsonObject { put("edited_at", "2026-11-16T04:30:00Z") }))
        assertEquals("false", deleted(l))
        assertEquals("kept", text(l))
    }

    @Test
    fun existingLinePutIsSkipped() = app {
        val e = day(); val l = UUID.randomUUID()
        ok(a, op("PUT", "journal_lines", l, line(e, "first", "2026-11-16T04:30:00Z")))
        ok(a, op("PATCH", "journal_lines", l, buildJsonObject { put("text", "edited"); put("edited_at", "2026-11-16T04:35:00Z") }))
        // A second device moving the same old text creates the same line id: what is there stays.
        ok(a, op("PUT", "journal_lines", l, line(e, "first", "2026-11-16T04:30:00Z")))
        assertEquals("edited", text(l))
    }

    @Test
    fun lineOnAnotherUsersDayIsRefused() = app {
        val e = day(); val l = UUID.randomUUID()
        val r = upload(b, op("PUT", "journal_lines", l, line(e, "not mine", "2026-11-16T04:30:00Z")))
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status, r.bodyAsText())
    }
}
