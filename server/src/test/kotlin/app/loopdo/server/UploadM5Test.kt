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
import kotlinx.serialization.json.JsonNull
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

/** M5 server rules (design 2026-11-16): pause, targets from tomorrow, late_since, repeating instances. */
class UploadM5Test {
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
    private val monMorning = at("2026-11-16T03:30:00Z") // Mon 09:00 IST: today = 16 nov, tomorrow = 17 nov

    /** A habit of A with a daily schedule since 1 nov, written as fixtures (not through the API). */
    private fun fixtureHabit(): UUID {
        val h = UUID.randomUUID()
        TestDb.admin("""
            insert into public.habits (id, user_id, title, kind, target) values ('$h', '$a', 'gym', 'yes_no', 1);
            insert into public.habit_schedules (user_id, habit_id, valid_from, recurrence) values ('$a', '$h', '2026-11-01', 'daily');
        """.trimIndent())
        return h
    }

    private fun schedule(h: UUID, from: String, recurrence: String, ev: JsonObject? = null, target: Int? = null) = buildJsonObject {
        put("habit_id", h.toString()); put("valid_from", from); put("recurrence", recurrence)
        if (target != null) put("target", target)
        if (ev != null) put("evidence", ev)
    }

    private fun versions(h: UUID) = TestDb.scalar(
        "select string_agg(valid_from || ':' || recurrence, ',' order by valid_from) from public.habit_schedules where habit_id = '$h'")

    // Made offline at 23:00 IST on Sat 14 nov (verified by a signed anchor), synced Monday.
    private fun saturdayNight() = evidence(a, "2026-11-14", "2026-11-14T17:30:00Z", "2026-11-14T12:30:00Z", 1_000_000, 5, 19_000_000, 5)

    @Test
    fun pausedRecurrenceAccepted() = app(monMorning) {
        val h = fixtureHabit()
        val r = upload(a, op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-17", "paused")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("2026-11-01:daily,2026-11-17:paused", versions(h))
    }

    @Test
    fun scheduleWithVerifiedEvidenceKeepsItsDate() = app(monMorning) {
        // A pause set at the airport on Sat night for Sun–Tue, synced only on Monday: it keeps its dates.
        val h = fixtureHabit()
        val r = upload(a,
            op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-15", "paused", saturdayNight())),
            op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-18", "daily", saturdayNight())))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("2026-11-01:daily,2026-11-15:paused,2026-11-18:daily", versions(h))
    }

    @Test
    fun scheduleWithoutEvidenceIsClampedToTomorrow() = app(monMorning) {
        val h = fixtureHabit()
        upload(a, op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-15", "paused")))
        assertEquals("2026-11-01:daily,2026-11-17:paused", versions(h))
    }

    @Test
    fun pauseVersionsNeverCollide() = app(monMorning) {
        // Both clamp to tomorrow; the resume version is shifted a day instead of a 422.
        val h = fixtureHabit()
        val r = upload(a,
            op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-15", "paused")),
            op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-16", "daily")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("2026-11-01:daily,2026-11-17:paused,2026-11-18:daily", versions(h))
    }

    @Test
    fun scheduleTargetAndHabitStepUnitAccepted() = app(monMorning) {
        val h = UUID.randomUUID()
        val data = buildJsonObject { put("title", "japanese vocab"); put("kind", "count"); put("target", 35); put("step", 5); put("unit", "words") }
        assertEquals(HttpStatusCode.OK, upload(a, op("PUT", "habits", h, data)).status)
        upload(a, op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-16", "daily")))
        val r = upload(a, op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-17", "daily", target = 50)))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("5|words|50", TestDb.scalar(
            "select h.step || '|' || h.unit || '|' || s.target from public.habits h join public.habit_schedules s on s.habit_id = h.id where h.id = '$h' and s.valid_from = '2026-11-17'"))
    }

    @Test
    fun archiveIsClampedToTomorrow() = app(monMorning) {
        val h = fixtureHabit()
        upload(a, op("PATCH", "habits", h, buildJsonObject { put("archived_on", "2026-11-16") }))
        assertEquals("2026-11-17", TestDb.scalar("select archived_on from public.habits where id = '$h'"))
    }

    @Test
    fun restorePauseOnTheArchiveDayIsKept() = app(monMorning) {
        // Archived since 10 nov, restored on mon 16 nov: the archived stretch becomes a pause that starts
        // on the archive day (already unjudged, so nothing in the past changes) and it is due from tomorrow.
        val h = fixtureHabit()
        TestDb.admin("update public.habits set archived_on = '2026-11-10' where id = '$h'")
        val r = upload(
            a,
            op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-10", "paused")),
            op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-17", "daily")),
            op("PATCH", "habits", h, buildJsonObject { put("archived_on", JsonNull) }),
        )
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("2026-11-01:daily,2026-11-10:paused,2026-11-17:daily", versions(h))
        assertEquals("t", TestDb.scalar("select archived_on is null from public.habits where id = '$h'"))
    }

    @Test
    fun pastPauseOffTheArchiveDayIsStillClamped() = app(monMorning) {
        val h = fixtureHabit()
        TestDb.admin("update public.habits set archived_on = '2026-11-10' where id = '$h'")
        upload(a, op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-12", "paused")))
        upload(a, op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-09", "daily", target = 1)))
        assertEquals("2026-11-01:daily,2026-11-17:paused,2026-11-18:daily", versions(h))
    }

    /** A task due 14 nov, completed late on 15 nov (fixtures, not through the API). */
    private fun completedLateTask(): UUID {
        val t = UUID.randomUUID()
        TestDb.admin("""
            insert into public.tasks (id, user_id, title, due_date, completed_at, completed_on, verified)
            values ('$t', '$a', 'fix bike brake', '2026-11-14', '2026-11-15T10:00:00Z', '2026-11-15', true);
        """.trimIndent())
        return t
    }

    @Test
    fun dueDateChangeOnCompletedTaskIsRefused() = app(monMorning) {
        // Review finding 1: re-dating a done late task would turn "done late" into "done on time".
        val t = completedLateTask()
        assertRejected(upload(a, op("PATCH", "tasks", t, buildJsonObject { put("due_date", "2026-11-16") })).bodyAsText(), "FORBIDDEN", 0)
        assertRejected(upload(a, op("PATCH", "tasks", t, buildJsonObject { put("due_date", JsonNull) })).bodyAsText(), "FORBIDDEN", 0)
        assertEquals("2026-11-14", TestDb.scalar("select due_date from public.tasks where id = '$t'"))
        // Other edits on a done task are fine (the title is not judged).
        assertEquals(HttpStatusCode.OK, upload(a, op("PATCH", "tasks", t, buildJsonObject { put("title", "fixed the bike brake") })).status)
    }

    @Test
    fun completedTaskDeleteIsRefused() = app(monMorning) {
        // Review finding 2: its completion is a past active day; deleting it would re-judge that day.
        val t = completedLateTask()
        assertRejected(upload(a, op("DELETE", "tasks", t)).bodyAsText(), "FORBIDDEN", 0)
        assertEquals("1", TestDb.scalar("select count(*) from public.tasks where id = '$t'"))
        val open = UUID.randomUUID()
        upload(a, op("PUT", "tasks", open, buildJsonObject { put("title", "read compose docs") }))
        assertEquals(HttpStatusCode.OK, upload(a, op("DELETE", "tasks", open)).status)
        assertEquals("0", TestDb.scalar("select count(*) from public.tasks where id = '$open'"))
    }

    @Test
    fun existingInstancePutIsSkipped() = app(monMorning) {
        // Review finding 3: a second device creating the same occurrence must not reset the first one's edits.
        val tpl = UUID.randomUUID(); val inst = UUID.randomUUID()
        val instance = buildJsonObject {
            put("title", "pay rent"); put("template_id", tpl.toString()); put("occurrence_date", "2026-11-16"); put("due_date", "2026-11-16")
        }
        upload(a,
            op("PUT", "tasks", tpl, buildJsonObject { put("title", "pay rent"); putJsonObject("repeat_rule") { put("type", "daily") } }),
            op("PUT", "tasks", inst, instance))
        upload(a, op("PATCH", "tasks", inst, buildJsonObject { put("title", "pay rent (landlord)"); put("due_date", "2026-11-20") }))
        val again = upload(a, op("PUT", "tasks", inst, instance))
        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
        assertEquals("pay rent (landlord)|2026-11-20", TestDb.scalar("select title || '|' || due_date from public.tasks where id = '$inst'"))
    }

    @Test
    fun existingSubtaskPutIsSkipped() = app(monMorning) {
        val t = UUID.randomUUID(); val s = UUID.randomUUID()
        upload(a, op("PUT", "tasks", t, buildJsonObject { put("title", "pay rent") }))
        val item = buildJsonObject { put("task_id", t.toString()); put("title", "check the amount"); put("done", 0) }
        upload(a, op("PUT", "subtasks", s, item))
        upload(a, op("PATCH", "subtasks", s, buildJsonObject { put("done", 1) }))
        assertEquals(HttpStatusCode.OK, upload(a, op("PUT", "subtasks", s, item)).status)
        assertEquals("t", TestDb.scalar("select done from public.subtasks where id = '$s'"))
    }

    @Test
    fun firstVersionProvenByEvidenceKeepsItsDay() = app(monMorning) {
        // Review finding 8: a habit created offline on sat 14 nov and synced on monday starts on 14 nov,
        // so its ticks of 14–15 nov count.
        val h = UUID.randomUUID()
        val r = upload(a,
            op("PUT", "habits", h, habit("stretch")),
            op("PUT", "habit_schedules", UUID.randomUUID(), schedule(h, "2026-11-14", "daily", saturdayNight())))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("2026-11-14:daily", versions(h))
        // Without verified evidence it still starts today.
        val g = UUID.randomUUID()
        upload(a, op("PUT", "habits", g, habit("walk")), op("PUT", "habit_schedules", UUID.randomUUID(), schedule(g, "2026-11-14", "daily")))
        assertEquals("2026-11-16:daily", versions(g))
    }

    @Test
    fun lateSinceIsKeptAndServerOwned() = app(monMorning) {
        val t = UUID.randomUUID()
        upload(a, op("PUT", "tasks", t, buildJsonObject { put("title", "fix bike brake"); put("due_date", "2026-11-14") }))
        upload(a, op("PATCH", "tasks", t, buildJsonObject { put("due_date", "2026-11-17") })) // late since 14 nov, moved
        upload(a, op("PATCH", "tasks", t, buildJsonObject { put("due_date", "2026-11-20") })) // moved again: still 14 nov
        assertEquals("2026-11-14", TestDb.scalar("select late_since from public.tasks where id = '$t'"))
        val r = upload(a, op("PATCH", "tasks", t, buildJsonObject { put("late_since", JsonNull) }))
        assertRejected(r.bodyAsText(), "BAD_OP", 0)
        // A task moved before it was due never becomes late.
        val u = UUID.randomUUID()
        upload(a, op("PUT", "tasks", u, buildJsonObject { put("title", "call home"); put("due_date", "2026-11-18") }))
        upload(a, op("PATCH", "tasks", u, buildJsonObject { put("due_date", "2026-11-19") }))
        assertEquals("", TestDb.scalar("select coalesce(late_since::text, '') from public.tasks where id = '$u'"))
    }

    @Test
    fun recreatedDeletedInstanceIsIgnored() = app(monMorning) {
        val tpl = UUID.randomUUID(); val inst = UUID.randomUUID()
        val instance = buildJsonObject {
            put("title", "pay rent"); put("template_id", tpl.toString()); put("occurrence_date", "2026-11-16"); put("due_date", "2026-11-16")
        }
        val r = upload(a,
            op("PUT", "tasks", tpl, buildJsonObject { put("title", "pay rent"); putJsonObject("repeat_rule") { put("type", "daily") } }),
            op("PUT", "tasks", inst, instance),
            op("PATCH", "tasks", tpl, buildJsonObject { put("generated_through", "2026-11-16") }))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        upload(a, op("DELETE", "tasks", inst))
        // A device that hasn't seen the delete creates the same occurrence again: ignored, not an error.
        val again = upload(a, op("PUT", "tasks", inst, instance))
        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
        assertEquals("0", TestDb.scalar("select count(*) from public.tasks where id = '$inst'"))
    }

    @Test
    fun generatedThroughKeepsTheMax() = app(monMorning) {
        val tpl = UUID.randomUUID()
        upload(a, op("PUT", "tasks", tpl, buildJsonObject { put("title", "water plants"); putJsonObject("repeat_rule") { put("type", "daily") } }))
        upload(a, op("PATCH", "tasks", tpl, buildJsonObject { put("generated_through", "2026-11-20") }))
        upload(a, op("PATCH", "tasks", tpl, buildJsonObject { put("generated_through", "2026-11-18") }))
        assertEquals("2026-11-20", TestDb.scalar("select generated_through from public.tasks where id = '$tpl'"))
    }
}
