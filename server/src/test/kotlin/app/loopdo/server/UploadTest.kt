package app.loopdo.server

import app.loopdo.shared.time.signAnchor
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class UploadTest {
    private val secret = "test-anchor-secret-32-bytes-long!!".toByteArray()
    private fun at(s: String) = Instant.parse(s)
    private val sunMorning = at("2026-11-15T03:30:00Z") // Sun 09:00 IST

    private fun app(now: Instant = sunMorning, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            loopdoModule(Deps(Db(TestDb.apiDataSource()), TestAuth.jwkProvider, TestAuth.ISSUER, secret) { now })
        }
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

    private fun body(r: String) = Json.parseToJsonElement(r).jsonObject

    private fun habit(title: String = "h-${UUID.randomUUID()}") = buildJsonObject {
        put("title", title); put("kind", "yes_no"); put("target", 1)
    }

    /** Evidence for a tick. anchor = (serverTime, elapsedMs, boot); a valid signature unless sig is given. */
    private fun evidence(
        user: UUID, claimed: String, wall: String, anchorTime: String?, anchorElapsed: Long, anchorBoot: Int,
        tickElapsed: Long, tickBoot: Int, sig: String? = null, network: String? = null,
    ) = buildJsonObject {
        put("claimedDate", claimed); put("wallClock", wall)
        if (network != null) put("networkTime", network)
        if (anchorTime != null) putJsonObject("anchor") {
            put("serverTime", anchorTime); put("elapsedRealtimeMs", anchorElapsed); put("bootCount", anchorBoot)
            put("sig", sig ?: signAnchor(secret, user.toString(), at(anchorTime)))
        }
        put("elapsedAtTickMs", tickElapsed); put("bootCountAtTick", tickBoot)
    }

    private fun log(habitId: UUID, date: String, count: Int, ev: JsonObject) = buildJsonObject {
        put("habit_id", habitId.toString()); put("loopdo_date", date); put("count", count)
        put("completed_at", ev["wallClock"]!!.jsonPrimitive.content); put("evidence", ev)
    }

    private fun assertRejected(r: String, code: String, opIndex: Int) {
        val b = body(r)
        assertEquals(code, b.getValue("code").jsonPrimitive.content, r)
        assertEquals(opIndex, b.getValue("opIndex").jsonPrimitive.int, r)
    }

    private val a = TestDb.userA
    private val b = TestDb.userB

    // 23:00 IST Sat, offline; anchor at 18:00 IST the same boot; synced Sunday morning.
    private fun eveningTick(user: UUID, sig: String? = null) = evidence(
        user, "2026-11-14", "2026-11-14T17:30:00Z", "2026-11-14T12:30:00Z", 1_000_000, 5, 19_000_000, 5, sig)

    @Test
    fun validOfflineTickAccepted() = app {
        val h = UUID.randomUUID(); val l = UUID.randomUUID()
        val r = upload(a, op("PUT", "habits", h, habit()), op("PUT", "habit_logs", l, log(h, "2026-11-14", 1, eveningTick(a))))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("t", TestDb.scalar("select verified from public.habit_logs where id = '$l'"))
    }

    @Test
    fun backdatedTickRejected422() = app {
        val h = UUID.randomUUID(); val l = UUID.randomUUID()
        upload(a, op("PUT", "habits", h, habit()))
        // Phone clock set back to Fri 22:00 IST, but the anchor proves it is Sunday 10:01 IST.
        val ev = evidence(a, "2026-11-13", "2026-11-13T16:30:00Z", "2026-11-15T04:30:00Z", 1_000_000, 5, 1_060_000, 5)
        val r = upload(a, op("PUT", "habit_logs", l, log(h, "2026-11-13", 1, ev)))
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertRejected(r.bodyAsText(), "BACKDATED", 0)
        assertEquals("0", TestDb.scalar("select count(*) from public.habit_logs where id = '$l'"))
    }

    @Test
    fun tickBeforeSettingsChangeAccepted() = app(now = at("2026-11-15T15:00:00Z")) {
        // B moved to New York (in force Sat 22:30 UTC). Tick made at Sat 05:00 IST, before the switch.
        val h = UUID.randomUUID()
        val ev = evidence(b, "2026-11-14", "2026-11-13T23:30:00Z", "2026-11-13T23:00:00Z", 1_000_000, 5, 2_800_000, 5)
        val r = upload(b, op("PUT", "habits", h, habit()), op("PUT", "habit_logs", UUID.randomUUID(), log(h, "2026-11-14", 1, ev)))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    @Test
    fun forgedAnchorSignatureRejected() = app {
        val h = UUID.randomUUID()
        val r = upload(a, op("PUT", "habits", h, habit()),
            op("PUT", "habit_logs", UUID.randomUUID(), log(h, "2026-11-14", 1, eveningTick(a, sig = "forged"))))
        assertRejected(r.bodyAsText(), "INVALID_EVIDENCE", 1)
    }

    @Test
    fun cannotWriteOtherUsersRows() = app {
        val r = upload(a, op("PUT", "habits", TestDb.habitB, habit("hacked")))
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertRejected(r.bodyAsText(), "FORBIDDEN", 0)
        assertEquals("gym", TestDb.scalar("select title from public.habits where id = '${TestDb.habitB}'"))
    }

    @Test
    fun unknownColumnRejected() = app {
        val data = buildJsonObject { put("title", "x"); put("kind", "yes_no"); put("target", 1); put("is_admin", true) }
        assertRejected(upload(a, op("PUT", "habits", UUID.randomUUID(), data)).bodyAsText(), "BAD_OP", 0)
    }

    @Test
    fun clientCannotSetVerified() = app {
        val h = UUID.randomUUID()
        val data = buildJsonObject {
            log(h, "2026-11-14", 1, eveningTick(a)).forEach { (k, v) -> put(k, v) }
            put("verified", true)
        }
        assertRejected(upload(a, op("PUT", "habits", h, habit()), op("PUT", "habit_logs", UUID.randomUUID(), data)).bodyAsText(), "BAD_OP", 1)
    }

    @Test
    fun invalidOpRejectsWholeBatch() = app {
        val list = UUID.randomUUID()
        val r = upload(a,
            op("PUT", "lists", list, buildJsonObject { put("name", "Work") }),
            op("PUT", "app_config", UUID.randomUUID(), buildJsonObject { put("signups_enabled", false) }))
        assertRejected(r.bodyAsText(), "BAD_OP", 1)
        assertEquals("0", TestDb.scalar("select count(*) from public.lists where id = '$list'"))
    }

    @Test
    fun replayedBatchIsIdempotent() = app {
        val h = UUID.randomUUID(); val l = UUID.randomUUID()
        val ops = arrayOf(op("PUT", "habits", h, habit("same")), op("PUT", "habit_logs", l, log(h, "2026-11-14", 1, eveningTick(a))))
        assertEquals(HttpStatusCode.OK, upload(a, *ops).status)
        assertEquals(HttpStatusCode.OK, upload(a, *ops).status)
        assertEquals("1", TestDb.scalar("select count(*) from public.habit_logs where habit_id = '$h'"))
    }

    @Test
    fun rebootedOfflineTickStoredUnverified() = app {
        val h = UUID.randomUUID(); val l = UUID.randomUUID()
        // Boot 6 != anchor boot 5 and no network time: wall clock says Sat 21:00 IST.
        val ev = evidence(a, "2026-11-14", "2026-11-14T15:30:00Z", "2026-11-14T12:30:00Z", 1_000_000, 5, 50_000, 6)
        assertEquals(HttpStatusCode.OK, upload(a, op("PUT", "habits", h, habit()), op("PUT", "habit_logs", l, log(h, "2026-11-14", 1, ev))).status)
        assertEquals("f", TestDb.scalar("select verified from public.habit_logs where id = '$l'"))
    }

    private fun sched(h: UUID, from: String) = buildJsonObject {
        put("habit_id", h.toString()); put("valid_from", from); put("recurrence", "daily")
    }

    @Test
    fun scheduleVersionsNeverRewriteThePast() = app { // today = Sun 15 Nov
        val h = UUID.randomUUID(); val first = UUID.randomUUID(); val second = UUID.randomUUID()
        assertEquals(HttpStatusCode.OK, upload(a, op("PUT", "habits", h, habit()), op("PUT", "habit_schedules", first, sched(h, "2026-11-15"))).status)
        // A later version asking to start in the past is moved to tomorrow, never earlier.
        assertEquals(HttpStatusCode.OK, upload(a, op("PUT", "habit_schedules", second, sched(h, "2026-11-10"))).status)
        assertEquals("2026-11-16", TestDb.scalar("select valid_from from public.habit_schedules where id = '$second'"))
        // A version already in force can't be changed...
        val changed = buildJsonObject { sched(h, "2026-11-15").forEach { (k, v) -> put(k, v) }; put("recurrence", "weekdays"); put("weekdays", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(1)) }) }
        assertRejected(upload(a, op("PUT", "habit_schedules", first, changed)).bodyAsText(), "FORBIDDEN", 0)
        // ...but replaying it unchanged (a retry) is fine.
        assertEquals(HttpStatusCode.OK, upload(a, op("PUT", "habit_schedules", first, sched(h, "2026-11-15"))).status)
    }

    @Test
    fun offlineFirstScheduleSyncedAfterMidnightIsMovedToToday() = app { // made Sat 23:50, synced Sun
        val h = UUID.randomUUID(); val s1 = UUID.randomUUID()
        val r = upload(a, op("PUT", "habits", h, habit()), op("PUT", "habit_schedules", s1, sched(h, "2026-11-14")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("2026-11-15", TestDb.scalar("select valid_from from public.habit_schedules where id = '$s1'"))
    }

    @Test
    fun targetFrozenOnceHabitHasLogs() = app {
        val h = UUID.randomUUID()
        val water = buildJsonObject { put("title", "water"); put("kind", "count"); put("target", 8) }
        assertEquals(HttpStatusCode.OK, upload(a, op("PUT", "habits", h, water)).status)
        // No logs yet: the target may still change.
        assertEquals(HttpStatusCode.OK, upload(a, op("PATCH", "habits", h, buildJsonObject { put("target", 10) })).status)
        val ev = eveningTick(a)
        val partial = buildJsonObject { log(h, "2026-11-14", 5, ev).forEach { (k, v) -> put(k, v) } }
        assertEquals(HttpStatusCode.OK, upload(a, op("PUT", "habit_logs", UUID.randomUUID(), partial)).status)
        // With history, lowering the target would turn past partial days into successes.
        assertRejected(upload(a, op("PATCH", "habits", h, buildJsonObject { put("target", 1) })).bodyAsText(), "FORBIDDEN", 0)
        // Renaming is still fine.
        assertEquals(HttpStatusCode.OK, upload(a, op("PATCH", "habits", h, buildJsonObject { put("title", "drink water") })).status)
    }

    @Test
    fun archivingInThePastIsMovedToToday() = app {
        val h = UUID.randomUUID()
        assertEquals(HttpStatusCode.OK, upload(a, op("PUT", "habits", h, habit()), op("PATCH", "habits", h, buildJsonObject { put("archived_on", "2020-01-01") })).status)
        assertEquals("2026-11-15", TestDb.scalar("select archived_on from public.habits where id = '$h'"))
    }

    @Test
    fun malformedValueIs422Not500() = app {
        val h = UUID.randomUUID()
        val bad = buildJsonObject {
            put("habit_id", h.toString()); put("valid_from", "2026-11-15"); put("recurrence", "weekdays")
            put("weekdays", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("x", 1) }) })
        }
        val r = upload(a, op("PUT", "habits", h, habit()), op("PUT", "habit_schedules", UUID.randomUUID(), bad))
        assertRejected(r.bodyAsText(), "BAD_OP", 1)
    }

    @Test
    fun anchorSignedWithPreviousSecretStillAccepted() = testApplication {
        val old = "previous-anchor-secret-32-bytes-long!".toByteArray()
        application {
            loopdoModule(Deps(Db(TestDb.apiDataSource()), TestAuth.jwkProvider, TestAuth.ISSUER,
                "rotated-anchor-secret-32-bytes-long!!".toByteArray(), previousAnchorSecret = old) { sunMorning })
        }
        val h = UUID.randomUUID()
        val ev = evidence(a, "2026-11-14", "2026-11-14T17:30:00Z", "2026-11-14T12:30:00Z", 1_000_000, 5, 19_000_000, 5,
            sig = signAnchor(old, a.toString(), at("2026-11-14T12:30:00Z")))
        val r = upload(a, op("PUT", "habits", h, habit()), op("PUT", "habit_logs", UUID.randomUUID(), log(h, "2026-11-14", 1, ev)))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }
}
