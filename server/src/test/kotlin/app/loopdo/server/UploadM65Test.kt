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

    private fun op(op: String, table: String, id: UUID, data: JsonObject? = null, old: JsonObject? = null, key: String? = "k-${UUID.randomUUID()}") = buildJsonObject {
        put("op", op); put("table", table); put("id", id.toString())
        if (data != null) put("data", data)
        if (old != null) put("old", old)
        if (key != null) put("key", key)
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

    // --- counts add up (M6.5) ---------------------------------------------------

    /** A tick on Mon 16 nov 09:00 IST, verified by a signed anchor. */
    private fun tick() = buildJsonObject {
        put("claimedDate", "2026-11-16"); put("wallClock", "2026-11-16T03:29:00Z")
        putJsonObject("anchor") {
            put("serverTime", "2026-11-16T03:00:00Z"); put("elapsedRealtimeMs", 1_000_000); put("bootCount", 5)
            put("sig", signAnchor(secret, a.toString(), Instant.parse("2026-11-16T03:00:00Z")))
        }
        put("elapsedAtTickMs", 2_740_000); put("bootCountAtTick", 5)
    }

    private fun habit(kind: String, target: Int): UUID {
        val h = UUID.randomUUID()
        TestDb.admin("""
            insert into public.habits (id, user_id, title, kind, target) values ('$h', '$a', 'h', '$kind', $target);
            insert into public.habit_schedules (user_id, habit_id, valid_from, recurrence) values ('$a', '$h', '2026-11-01', 'daily');
        """.trimIndent())
        return h
    }

    private fun logPut(h: UUID, count: Int) = buildJsonObject {
        put("habit_id", h.toString()); put("loopdo_date", "2026-11-16"); put("count", count)
        put("completed_at", "2026-11-16T03:29:00Z"); put("evidence", tick())
    }

    private fun countPatch(count: Int) = buildJsonObject {
        put("count", count); put("completed_at", if (count > 0) "2026-11-16T03:29:00Z" else null); put("evidence", tick())
    }

    private fun count(log: UUID) = TestDb.scalar("select count from public.habit_logs where id = '$log'")

    @Test
    fun countPatchAppliesTheDifference() = app {
        val h = habit("count", 8); val log = UUID.randomUUID()
        ok(a, op("PUT", "habit_logs", log, logPut(h, 3)))
        ok(a, op("PATCH", "habit_logs", log, countPatch(4), old = buildJsonObject { put("count", 3) })) // the phone
        ok(a, op("PATCH", "habit_logs", log, countPatch(4), old = buildJsonObject { put("count", 3) })) // the laptop
        assertEquals("5", count(log))
    }

    @Test
    fun countPutOnAnExistingLogAdds() = app {
        val h = habit("count", 8); val log = UUID.randomUUID()
        ok(a, op("PUT", "habit_logs", log, logPut(h, 2))) // both devices created today's log (same id)
        ok(a, op("PUT", "habit_logs", log, logPut(h, 1)))
        assertEquals("3", count(log))
    }

    @Test
    fun yesNoTickedOnTwoDevicesStaysOne() = app {
        val h = habit("yes_no", 1); val log = UUID.randomUUID()
        ok(a, op("PUT", "habit_logs", log, logPut(h, 1)))
        ok(a, op("PUT", "habit_logs", log, logPut(h, 1)))
        assertEquals("1", count(log))
    }

    @Test
    fun countNeverGoesBelowZeroAndClearsCompletion() = app {
        val h = habit("count", 8); val log = UUID.randomUUID()
        ok(a, op("PUT", "habit_logs", log, logPut(h, 1)))
        ok(a, op("PATCH", "habit_logs", log, countPatch(0), old = buildJsonObject { put("count", 1) }))
        ok(a, op("PATCH", "habit_logs", log, countPatch(0), old = buildJsonObject { put("count", 1) }))
        assertEquals("0", count(log))
        assertEquals("true", TestDb.scalar("select (completed_at is null)::text from public.habit_logs where id = '$log'"))
    }

    @Test
    fun countWithoutPreviousValuesIsAbsolute() = app {
        val h = habit("count", 8); val log = UUID.randomUUID()
        ok(a, op("PUT", "habit_logs", log, logPut(h, 3)))
        ok(a, op("PATCH", "habit_logs", log, countPatch(6), key = null)) // an older app version: no old values, no key
        assertEquals("6", count(log))
    }

    @Test
    fun aRetriedCountChangeIsAppliedOnce() = app {
        val h = habit("count", 8); val log = UUID.randomUUID()
        ok(a, op("PUT", "habit_logs", log, logPut(h, 3), key = "phone:1"))
        val tap = op("PATCH", "habit_logs", log, countPatch(4), old = buildJsonObject { put("count", 3) }, key = "phone:2")
        ok(a, tap)
        ok(a, tap) // the reply was lost; the phone sends the same change again
        ok(a, op("PUT", "habit_logs", log, logPut(h, 3), key = "phone:1"))
        assertEquals("4", count(log))
    }

    // --- the latest schedule replace wins (M6.5; M5 minors 6 and 7) -------------

    private fun versions(h: UUID) = TestDb.scalar(
        "select string_agg(valid_from || ':' || recurrence, ',' order by valid_from) from public.habit_schedules where habit_id = '$h'")

    private fun version(h: UUID, from: String, recurrence: String) = buildJsonObject {
        put("habit_id", h.toString()); put("valid_from", from); put("recurrence", recurrence)
    }

    @Test
    fun aSecondReplaceOfAPendingVersionWins() = app {
        val h = habit("yes_no", 1)
        ok(a, op("PUT", "habit_schedules", UUID.randomUUID(), version(h, "2026-11-17", "paused"))) // the phone
        // The laptop, not having seen it, also sets tomorrow's version: the later one wins the slot.
        ok(a, op("PUT", "habit_schedules", UUID.randomUUID(), version(h, "2026-11-17", "daily")))
        assertEquals("2026-11-01:daily,2026-11-17:daily", versions(h))
    }

    @Test
    fun replacingAVersionThatTookEffectMakesTheNextOne() = app {
        val h = habit("yes_no", 1)
        val pending = UUID.randomUUID()
        TestDb.admin("insert into public.habit_schedules (id, user_id, habit_id, valid_from, recurrence) values ('$pending', '$a', '$h', '2026-11-16', 'paused')")
        // Made on Sunday to replace Monday's version, uploaded on Monday: that version is history now, the new one starts tomorrow.
        ok(a, op("DELETE", "habit_schedules", pending), op("PUT", "habit_schedules", UUID.randomUUID(), version(h, "2026-11-16", "daily")))
        assertEquals("2026-11-01:daily,2026-11-16:paused,2026-11-17:daily", versions(h))
    }

    // --- M5 items 4 and 5: offline moves and archives keep the day they were made (verified evidence) ---------

    /** Evidence of a change made at 10:30 IST on [day] (verified by a signed anchor that morning). */
    private fun madeOn(day: String) = buildJsonObject {
        put("claimedDate", day); put("wallClock", "${day}T05:00:00Z")
        putJsonObject("anchor") {
            put("serverTime", "${day}T03:00:00Z"); put("elapsedRealtimeMs", 1_000_000); put("bootCount", 5)
            put("sig", signAnchor(secret, a.toString(), Instant.parse("${day}T03:00:00Z")))
        }
        put("elapsedAtTickMs", 8_200_000); put("bootCountAtTick", 5)
    }

    private fun openTask(due: String): UUID {
        val t = UUID.randomUUID()
        TestDb.admin("insert into public.tasks (id, user_id, title, due_date) values ('$t', '$a', 'call home', '$due')")
        return t
    }

    @Test
    fun anOnTimeMoveMadeOfflineIsNotLate() = app {
        val t = openTask("2026-11-15")
        // Moved on its due day (sun 15), offline; uploaded monday.
        ok(a, op("PATCH", "tasks", t, buildJsonObject { put("due_date", "2026-11-20"); put("evidence", madeOn("2026-11-15")) }))
        assertEquals("", TestDb.scalar("select coalesce(late_since::text, '') from public.tasks where id = '$t'"))
        assertEquals("", TestDb.scalar("select coalesce(evidence::text, '') from public.tasks where id = '$t'")) // used, not stored
    }

    @Test
    fun aMoveWithoutProofIsStillLate() = app {
        val t = openTask("2026-11-15")
        ok(a, op("PATCH", "tasks", t, buildJsonObject { put("due_date", "2026-11-20") }))
        assertEquals("2026-11-15", TestDb.scalar("select late_since from public.tasks where id = '$t'"))
    }

    @Test
    fun anArchiveMadeOfflineKeepsItsDay() = app {
        val h = habit("yes_no", 1)
        // Archived on sun 15 (from mon 16), offline; uploaded monday: it starts monday, not tuesday.
        ok(a, op("PATCH", "habits", h, buildJsonObject { put("archived_on", "2026-11-16"); put("evidence", madeOn("2026-11-15")) }))
        assertEquals("2026-11-16", TestDb.scalar("select archived_on from public.habits where id = '$h'"))
    }

    @Test
    fun anArchiveWithoutProofIsStillMovedToTomorrow() = app {
        val h = habit("yes_no", 1)
        ok(a, op("PATCH", "habits", h, buildJsonObject { put("archived_on", "2026-11-16") }))
        assertEquals("2026-11-17", TestDb.scalar("select archived_on from public.habits where id = '$h'"))
    }

    // --- final review (M6.5) -------------------------------------------------------------------------------

    // Important 1: two devices uploading at the same moment both count (no lost tap between read and write).
    @Test
    fun simultaneousTapsFromManyDevicesAllCount() = app {
        val h = habit("count", 100); val log = UUID.randomUUID()
        ok(a, op("PUT", "habit_logs", log, logPut(h, 3)))
        kotlinx.coroutines.coroutineScope {
            (1..12).map {
                async { upload(a, op("PATCH", "habit_logs", log, countPatch(4), old = buildJsonObject { put("count", 3) })) }
            }.awaitAll().forEach { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        }
        assertEquals("15", count(log))
    }

    @Test
    fun simultaneousFirstTapsBothCount() = app {
        repeat(5) {
            val h = habit("count", 100) // one log per habit and day: a fresh habit each round
            val log = UUID.randomUUID()
            kotlinx.coroutines.coroutineScope {
                listOf(async { upload(a, op("PUT", "habit_logs", log, logPut(h, 1))) }, async { upload(a, op("PUT", "habit_logs", log, logPut(h, 1))) })
                    .awaitAll().forEach { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
            }
            assertEquals("2", count(log))
        }
    }

    // Important 3: a field the server doesn't know yet (a newer app) is ignored, never a refusal that discards the change.
    @Test
    fun anUnknownFieldFromANewerAppIsIgnored() = app {
        val e = day(); val l = UUID.randomUUID()
        val withExtra = JsonObject(op("PUT", "journal_lines", l, line(e, "from a newer app", "2026-11-16T04:30:00Z")) + ("future_field" to kotlinx.serialization.json.JsonPrimitive(1)))
        ok(a, withExtra)
        assertEquals("from a newer app", text(l))
    }
}
