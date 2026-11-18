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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.putJsonObject
import app.loopdo.shared.time.signAnchor
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8 (design 2026-11-18): the weekly focus — set only on its Sunday-before or Monday, at most 2, frozen after. */
class UploadM8Test {
    private val secret = "test-anchor-secret-32-bytes-long!!".toByteArray()
    private val monMorning = Instant.parse("2026-11-16T03:30:00Z") // Mon 16 nov 09:00 IST = LoopDo mon 16
    private val a = TestDb.userA

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        TestDb.admin("delete from public.weekly_focus where user_id = '$a'") // tests share the database; each starts clean
        application { loopdoModule(Deps(Db(TestDb.apiDataSource()), TestAuth.jwkProvider, TestAuth.ISSUER, secret) { monMorning }) }
        block()
    }

    private fun op(op: String, id: UUID, data: JsonObject) = buildJsonObject {
        put("op", op); put("table", "weekly_focus"); put("id", id.toString()); put("data", data); put("key", "k-${UUID.randomUUID()}")
    }

    private suspend fun ApplicationTestBuilder.upload(vararg ops: JsonObject): HttpResponse =
        client.post("/api/sync/upload") {
            bearerAuth(TestAuth.token(a))
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("ops", buildJsonArray { ops.forEach { add(it) } }) }.toString())
        }

    private suspend fun ApplicationTestBuilder.ok(vararg ops: JsonObject) {
        val r = upload(*ops)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.refused(code: String, vararg ops: JsonObject) {
        val r = upload(*ops)
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status, r.bodyAsText())
        assertTrue(code in r.bodyAsText(), r.bodyAsText())
    }

    /** Verified evidence of a change made at 10:30 IST on [day]. */
    private fun madeOn(day: String) = buildJsonObject {
        put("claimedDate", day); put("wallClock", "${day}T05:00:00Z")
        putJsonObject("anchor") {
            put("serverTime", "${day}T03:00:00Z"); put("elapsedRealtimeMs", 1_000_000); put("bootCount", 5)
            put("sig", signAnchor(secret, a.toString(), Instant.parse("${day}T03:00:00Z")))
        }
        put("elapsedAtTickMs", 8_200_000); put("bootCountAtTick", 5)
    }

    /** Evidence with no trustworthy clock (rebooted offline): accepted, unverified. */
    private fun unverifiedOn(day: String) = buildJsonObject {
        put("claimedDate", day); put("wallClock", "${day}T05:00:00Z"); put("elapsedAtTickMs", 1000); put("bootCountAtTick", 9)
    }

    private fun habit(): UUID {
        val h = UUID.randomUUID()
        TestDb.admin("insert into public.habits (id, user_id, title, kind, target) values ('$h', '$a', 'gym', 'yes_no', 1)")
        return h
    }

    private fun focusHabit(week: String, item: UUID, goal: Int = 4, evidence: JsonObject? = null) = buildJsonObject {
        put("week_start", week); put("kind", "habit"); put("item_id", item.toString()); put("title", "gym"); put("goal", goal); put("sort_order", 0)
        if (evidence != null) put("evidence", evidence)
    }

    private fun live(week: String) = TestDb.scalar("select count(*) from public.weekly_focus where user_id = '$a' and week_start = '$week' and deleted_at is null")

    @Test
    fun focusOnMondayAccepted() = app {
        val f = UUID.randomUUID()
        ok(op("PUT", f, focusHabit("2026-11-16", habit())))
        assertEquals("4", TestDb.scalar("select goal from public.weekly_focus where id = '$f'"))
    }

    @Test
    fun focusForAWeekNotOpenRefused() = app {
        refused("FOCUS_CLOSED", op("PUT", UUID.randomUUID(), focusHabit("2026-11-23", habit()))) // its window is sun 22 / mon 23
        refused("FOCUS_CLOSED", op("PUT", UUID.randomUUID(), focusHabit("2026-11-09", habit()))) // its window closed on mon 9
    }

    @Test
    fun offlineMondayEditAccepted() = app {
        ok(op("PUT", UUID.randomUUID(), focusHabit("2026-11-09", habit(), evidence = madeOn("2026-11-09"))))
        assertEquals("1", live("2026-11-09"))
    }

    @Test
    fun unverifiedEvidenceAccepted() = app {
        ok(op("PUT", UUID.randomUUID(), focusHabit("2026-11-09", habit(), evidence = unverifiedOn("2026-11-08"))))
    }

    @Test
    fun lateEditWithEvidenceOutsideTheWindowRefused() = app {
        refused("FOCUS_CLOSED", op("PUT", UUID.randomUUID(), focusHabit("2026-11-09", habit(), evidence = madeOn("2026-11-10"))))
    }

    @Test
    fun thirdFocusRefused() = app {
        val (f1, f2) = UUID.randomUUID() to UUID.randomUUID()
        ok(op("PUT", f1, focusHabit("2026-11-16", habit())), op("PUT", f2, focusHabit("2026-11-16", habit())))
        refused("FOCUS_FULL", op("PUT", UUID.randomUUID(), focusHabit("2026-11-16", habit())))
        ok(op("PATCH", f1, buildJsonObject { put("deleted_at", "2026-11-16T03:29:00Z") }))
        ok(op("PUT", UUID.randomUUID(), focusHabit("2026-11-16", habit())))
        assertEquals("2", live("2026-11-16"))
    }

    @Test
    fun weekStartMustBeMonday() = app {
        refused("INVALID_FOCUS", op("PUT", UUID.randomUUID(), focusHabit("2026-11-17", habit())))
    }

    @Test
    fun foreignItemForbidden() = app {
        refused("FORBIDDEN", op("PUT", UUID.randomUUID(), focusHabit("2026-11-16", TestDb.habitB)))
    }

    @Test
    fun taskFocusNeedsADeadlineInsideTheWeek() = app {
        val t = UUID.randomUUID()
        TestDb.admin("insert into public.tasks (id, user_id, title, due_date) values ('$t', '$a', 'pay rent', '2026-11-18')")
        fun task(deadline: String) = buildJsonObject {
            put("week_start", "2026-11-16"); put("kind", "task"); put("item_id", t.toString()); put("title", "pay rent"); put("deadline", deadline); put("sort_order", 0)
        }
        refused("INVALID_FOCUS", op("PUT", UUID.randomUUID(), task("2026-11-25")))
        ok(op("PUT", UUID.randomUUID(), task("2026-11-18")))
    }

    @Test
    fun removeIsSoftDeleteInWindowOnly() = app {
        val f = UUID.randomUUID()
        TestDb.admin("insert into public.weekly_focus (id, user_id, week_start, kind, item_id, title, goal) values ('$f', '$a', '2026-11-09', 'habit', '${habit()}', 'gym', 3)")
        refused("FOCUS_CLOSED", op("PATCH", f, buildJsonObject { put("deleted_at", "2026-11-16T03:29:00Z") }))
        refused("BAD_OP", buildJsonObject { put("op", "DELETE"); put("table", "weekly_focus"); put("id", f.toString()); put("key", "k") })
        refused("FORBIDDEN", op("PATCH", f, buildJsonObject { put("week_start", "2026-11-16") }))
    }
}
