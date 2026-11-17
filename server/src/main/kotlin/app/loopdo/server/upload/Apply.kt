package app.loopdo.server.upload

import app.loopdo.server.loadDaySettingsVersions
import app.loopdo.shared.day.DaySettingsVersion
import app.loopdo.shared.day.dateResolver
import app.loopdo.shared.time.ClaimResult
import app.loopdo.shared.time.TickEvidence
import app.loopdo.shared.time.TimeAnchor
import app.loopdo.shared.time.validateClaim
import app.loopdo.shared.time.verifyAnchor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One PowerSync CrudEntry, as the phone uploads it. */
@Serializable
data class CrudOp(val op: String, val table: String, val id: String, val data: JsonObject? = null)

/** The batch is refused as a whole; the phone rolls it back and tells the user why. */
class Rejected(val code: String, val opIndex: Int) : RuntimeException(code)

@Serializable
private data class AnchorDto(val serverTime: String, val elapsedRealtimeMs: Long, val bootCount: Int, val sig: String)

@Serializable
private data class EvidenceDto(
    val claimedDate: String,
    val wallClock: String,
    val networkTime: String? = null,
    val anchor: AnchorDto? = null,
    val elapsedAtTickMs: Long,
    val bootCountAtTick: Int,
)

private val lenient = Json { ignoreUnknownKeys = false }

/**
 * Applies one upload batch inside the caller's transaction (already running as
 * loopdo_user for [user]). Any rule failure throws [Rejected]; the transaction is
 * rolled back by [app.loopdo.server.Db.asUser].
 */
class Applier(
    private val c: Connection,
    private val user: UUID,
    private val anchorSecret: ByteArray,
    private val now: Instant,
    /** Accepted too, so ticks queued before a secret rotation still verify. */
    private val previousAnchorSecret: ByteArray? = null,
) {
    /** What a rule decided: the (possibly adjusted) data, server-set columns, or skip as already applied. */
    private class Prepared(val data: JsonObject, val extra: Map<String, String?> = emptyMap(), val skip: Boolean = false)

    private val versions: List<DaySettingsVersion> by lazy { loadDaySettingsVersions(c) }

    private fun dateAt(i: Int): (Instant) -> LocalDate {
        if (versions.isEmpty()) throw Rejected("SETTINGS_REQUIRED", i)
        return dateResolver(versions)
    }

    private fun today(i: Int): LocalDate = dateAt(i)(now)

    fun apply(i: Int, op: CrudOp) {
        val spec = TABLES[op.table] ?: throw Rejected("BAD_OP", i)
        if (op.op !in spec.ops) throw Rejected("BAD_OP", i)
        val id = runCatching { UUID.fromString(op.id) }.getOrElse { throw Rejected("BAD_OP", i) }
        val data = op.data ?: JsonObject(emptyMap())
        if (op.op != "DELETE" && !spec.columns.keys.containsAll(data.keys)) throw Rejected("BAD_OP", i)

        try {
            val p = when (spec.name) {
                "habits" -> habitRules(i, op.op, id, data)
                "habit_logs" -> Prepared(data, habitLogRules(i, op.op, id, data))
                "tasks" -> taskRules(i, op.op, id, data)
                "habit_schedules" -> scheduleRules(i, op.op, id, data)
                "journal_photos" -> { photoRules(i, op.op, data); Prepared(data) }
                else -> Prepared(data)
            }
            if (p.skip) return
            when (op.op) {
                "PUT" -> put(spec, id, p.data, p.extra)
                "PATCH" -> patch(spec, id, p.data, p.extra)
                "DELETE" -> delete(spec, id)
            }
        } catch (r: Rejected) {
            throw r
        } catch (e: SQLException) {
            throw Rejected(codeFor(e), i)
        } catch (e: RuntimeException) {
            // Malformed values (wrong JSON shapes etc.): a permanent client error, never a 500
            // that would leave the phone retrying the same batch forever.
            throw Rejected("BAD_OP", i)
        }
    }

    // --- rules -------------------------------------------------------------

    /** Every count change is a tick: it must be proven to happen on the log's own day. */
    private fun habitLogRules(i: Int, op: String, id: UUID, data: JsonObject): Map<String, String?> {
        val existingDate = queryOne("select loopdo_date from public.habit_logs where id = ?::uuid", id.toString()) {
            it.getObject(1, LocalDate::class.java)
        }
        val date = when (op) {
            "PUT" -> data.date("loopdo_date") ?: throw Rejected("BAD_OP", i)
            else -> existingDate ?: return emptyMap() // PATCH of a row that isn't there (deleted): no-op
        }
        if (existingDate != null && existingDate != date) throw Rejected("BAD_OP", i)
        if (op == "PATCH" && "count" !in data) return emptyMap()
        val verified = checkTick(i, data, date)
        return mapOf("verified" to verified.toString())
    }

    /**
     * Completing a task is a tick on the completion day. Un-completing a dated task is
     * only possible on that same day; an undated (inbox) task has no lock and can be
     * reopened any time (owner, 2026-11-15).
     */
    /**
     * M5 task rules, then the completion rules below:
     * - a deleted occurrence of a repeating task is never created again (its date is at or before
     *   the template's generated_through), even by a device that hasn't seen the delete;
     * - generated_through only moves forward;
     * - moving a task that is already late records the first date it was late (late_since,
     *   server-owned), so the miss stays on record (spec 6.5).
     */
    private fun taskRules(i: Int, op: String, id: UUID, data: JsonObject): Prepared {
        if (op == "DELETE") return Prepared(data)
        val existing = queryOne("select due_date, completed_at, late_since from public.tasks where id = ?::uuid", id.toString()) {
            Triple(it.getObject(1, LocalDate::class.java), it.getObject(2, java.time.OffsetDateTime::class.java), it.getObject(3, LocalDate::class.java))
        }
        val template = data.text("template_id")
        val occurrence = data.date("occurrence_date")
        if (op == "PUT" && existing == null && template != null && occurrence != null) {
            val generatedThrough = queryOne("select generated_through from public.tasks where id = ?::uuid", template) {
                it.getObject(1, LocalDate::class.java)
            }
            if (generatedThrough != null && occurrence <= generatedThrough) return Prepared(data, skip = true)
        }
        var d = data
        data.date("generated_through")?.let { g ->
            val current = queryOne("select generated_through from public.tasks where id = ?::uuid", id.toString()) {
                it.getObject(1, LocalDate::class.java)
            }
            if (current != null && current > g) d = d.with("generated_through", current.toString())
        }
        val extra = mutableMapOf<String, String?>()
        if ("due_date" in data && existing != null) {
            val (oldDue, completedAt, lateSince) = existing
            if (completedAt == null && lateSince == null && oldDue != null && oldDue < today(i) && data.date("due_date") != oldDue) {
                extra["late_since"] = oldDue.toString()
            }
        }
        extra += completionRules(i, id, data)
        return Prepared(d, extra)
    }

    private fun completionRules(i: Int, id: UUID, data: JsonObject): Map<String, String?> {
        if ("completed_at" !in data) return emptyMap()
        val existing = queryOne("select completed_on, due_date from public.tasks where id = ?::uuid", id.toString()) {
            it.getObject(1, LocalDate::class.java) to it.getObject(2, LocalDate::class.java)
        }
        val existingOn = existing?.first
        val undated = if ("due_date" in data) data["due_date"] is JsonNull else existing?.second == null
        val completing = data["completed_at"] !is JsonNull
        return if (completing) {
            val claimed = evidenceOf(i, data).claimedDate
            if (existingOn != null && existingOn != claimed) throw Rejected("BAD_OP", i)
            val verified = checkTick(i, data, claimed)
            mapOf("completed_on" to claimed.toString(), "verified" to verified.toString())
        } else {
            if (existingOn == null || undated) return mapOf("completed_on" to null, "verified" to null)
            checkTick(i, data, existingOn) // only on the day it was completed
            mapOf("completed_on" to null, "verified" to null)
        }
    }

    /**
     * Schedule versions already in force are history: they can't be changed or deleted
     * (an unchanged replay is skipped). A new version starts no earlier than tomorrow
     * (today for a habit's first one); an earlier date, e.g. made offline before midnight,
     * is moved up to that day instead of being refused.
     */
    private fun scheduleRules(i: Int, op: String, id: UUID, data: JsonObject): Prepared {
        val today = today(i)
        val existing = queryOne(
            "select valid_from, habit_id::text, recurrence, weekdays::text from public.habit_schedules where id = ?::uuid",
            id.toString(),
        ) { listOf(it.getObject(1, LocalDate::class.java).toString(), it.getString(2), it.getString(3), it.getString(4)) }
        if (existing != null && LocalDate.parse(existing[0]) <= today) {
            val sameContent = op == "PUT" &&
                data.text("habit_id") == existing[1] && data.text("recurrence") == existing[2] &&
                data["weekdays"]?.let { bindValue(it, Col.SMALLINT_ARRAY) } == existing[3]
            if (sameContent) return Prepared(data, skip = true)
            throw Rejected("FORBIDDEN", i)
        }
        if (op == "DELETE") return Prepared(data)
        val habitId = data.text("habit_id") ?: throw Rejected("BAD_OP", i)
        val from = data.date("valid_from") ?: throw Rejected("BAD_OP", i)
        val hasOther = queryOne(
            "select 1 from public.habit_schedules where habit_id = ?::uuid and id <> ?::uuid limit 1", habitId, id.toString(),
        ) { true } ?: false
        val earliest = if (hasOther) today.plusDays(1) else today
        // M5: a version proved (verified time evidence) to have been made before its first day keeps
        // its date, e.g. a pause set offline at the airport and synced days later.
        val provenDay = if (data["evidence"] is JsonObject) runCatching { verifiedDay(i, data) }.getOrNull() else null
        if (provenDay != null && from > provenDay) return Prepared(data)
        if (from >= earliest) return Prepared(data)
        // M5 restore: the archived stretch becomes a pause from the archive day. Those days were already
        // unjudged (archived), so keeping the past date changes nothing that was judged.
        if (data.text("recurrence") == "paused") {
            val archivedOn = queryOne("select archived_on from public.habits where id = ?::uuid", habitId) {
                it.getObject(1, LocalDate::class.java)
            }
            if (archivedOn != null && archivedOn == from && archivedOn <= today) return Prepared(data)
        }
        // Clamped: never onto a day another version already starts (a pause's resume version moves on a day).
        var at = earliest
        while (queryOne(
                "select 1 from public.habit_schedules where habit_id = ?::uuid and valid_from = ?::date and id <> ?::uuid",
                habitId, at.toString(), id.toString(),
            ) { true } != null
        ) at = at.plusDays(1)
        return Prepared(data.with("valid_from", at.toString()))
    }

    /** The day the evidence proves the change was made, or null if it isn't verified. */
    private fun verifiedDay(i: Int, data: JsonObject): LocalDate? {
        val e = evidenceOf(i, data)
        val r = validateClaim(e, dateAt(i), now)
        return if (r is ClaimResult.Accepted && r.verified) e.claimedDate else null
    }

    /**
     * A habit's kind and target are frozen once it has logs: changing them would re-judge
     * past days (e.g. target 8 -> 1 turns partial days into successes); a target change is a
     * new schedule version instead (M5). Archiving can't be backdated; an earlier date is moved
     * up to tomorrow.
     */
    private fun habitRules(i: Int, op: String, id: UUID, data: JsonObject): Prepared {
        if (op == "DELETE") return Prepared(data)
        if ("kind" in data || "target" in data) {
            val existing = queryOne("select kind, target from public.habits where id = ?::uuid", id.toString()) {
                it.getString(1) to it.getInt(2)
            }
            if (existing != null) {
                val kindChanged = "kind" in data && data.text("kind") != existing.first
                val targetChanged = "target" in data && data.text("target")?.toIntOrNull() != existing.second
                val hasLogs = queryOne("select 1 from public.habit_logs where habit_id = ?::uuid limit 1", id.toString()) { true } ?: false
                if ((kindChanged || targetChanged) && hasLogs) throw Rejected("FORBIDDEN", i)
            }
        }
        val archived = data.date("archived_on") ?: return Prepared(data)
        // Archiving takes effect from tomorrow at the earliest, so it can't dodge today (M5).
        val tomorrow = today(i).plusDays(1)
        return if (archived < tomorrow) Prepared(data.with("archived_on", tomorrow.toString())) else Prepared(data)
    }

    private fun photoRules(i: Int, op: String, data: JsonObject) {
        if (op == "PUT" && data.text("r2_key")?.startsWith("u/$user/") != true) throw Rejected("FORBIDDEN", i)
    }

    private fun evidenceOf(i: Int, data: JsonObject): TickEvidence {
        val raw = data["evidence"] as? JsonObject ?: throw Rejected("INVALID_EVIDENCE", i)
        return runCatching {
            val dto = lenient.decodeFromJsonElement<EvidenceDto>(raw)
            val anchor = dto.anchor?.let {
                val serverTime = Instant.parse(it.serverTime)
                val valid = verifyAnchor(anchorSecret, user.toString(), serverTime, it.sig) ||
                    (previousAnchorSecret != null && verifyAnchor(previousAnchorSecret, user.toString(), serverTime, it.sig))
                if (!valid) error("bad anchor signature")
                TimeAnchor(serverTime, it.elapsedRealtimeMs, it.bootCount)
            }
            TickEvidence(
                LocalDate.parse(dto.claimedDate), Instant.parse(dto.wallClock), dto.networkTime?.let(Instant::parse),
                anchor, dto.elapsedAtTickMs, dto.bootCountAtTick,
            )
        }.getOrElse { throw Rejected("INVALID_EVIDENCE", i) }
    }

    /** Returns whether the tick is verified; throws if it is refused. */
    private fun checkTick(i: Int, data: JsonObject, day: LocalDate): Boolean {
        val e = evidenceOf(i, data)
        if (e.claimedDate != day) throw Rejected("BACKDATED", i)
        return when (val r = validateClaim(e, dateAt(i), now)) {
            is ClaimResult.Accepted -> r.verified
            is ClaimResult.Rejected -> throw Rejected(r.reason.name, i)
        }
    }

    // --- SQL ---------------------------------------------------------------

    private fun put(spec: TableSpec, id: UUID, data: JsonObject, extra: Map<String, String?>) {
        val cols = data.keys.map { it to spec.columns.getValue(it) } + extra.keys.map { it to SERVER_COLUMNS.getValue(it) }
        val values = data.keys.map { bindValue(data.getValue(it), spec.columns.getValue(it)) } + extra.values
        val names = listOf("id", "user_id") + cols.map { it.first }
        val placeholders = listOf("?::uuid", "?::uuid") + cols.map { "?${it.second.cast}" }
        val update = if (cols.isEmpty()) "do nothing" else "do update set " + cols.joinToString { "${it.first} = excluded.${it.first}" }
        val sql = "insert into public.${spec.name} (${names.joinToString()}) values (${placeholders.joinToString()}) on conflict (id) $update"
        exec(sql, listOf(id.toString(), user.toString()) + values)
    }

    private fun patch(spec: TableSpec, id: UUID, data: JsonObject, extra: Map<String, String?>) {
        val cols = data.keys.map { it to spec.columns.getValue(it) } + extra.keys.map { it to SERVER_COLUMNS.getValue(it) }
        if (cols.isEmpty()) return
        val values = data.keys.map { bindValue(data.getValue(it), spec.columns.getValue(it)) } + extra.values
        val sql = "update public.${spec.name} set ${cols.joinToString { "${it.first} = ?${it.second.cast}" }} where id = ?::uuid"
        exec(sql, values + id.toString())
    }

    private fun delete(spec: TableSpec, id: UUID) = exec("delete from public.${spec.name} where id = ?::uuid", listOf(id.toString()))

    private fun exec(sql: String, values: List<String?>) {
        c.prepareStatement(sql).use { st ->
            values.forEachIndexed { k, v -> st.setString(k + 1, v) }
            st.executeUpdate()
        }
    }

    private fun <T> queryOne(sql: String, vararg args: String, read: (ResultSet) -> T): T? =
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { k, v -> st.setString(k + 1, v) }
            st.executeQuery().use { rs -> if (rs.next()) read(rs) else null }
        }
}

private fun bindValue(v: JsonElement, col: Col): String? = when {
    v is JsonNull -> null
    col == Col.JSONB -> v.toString()
    col == Col.SMALLINT_ARRAY -> (v as? JsonArray)?.joinToString(",", "{", "}") { it.jsonPrimitive.content }
        ?: error("expected an array")
    v is JsonPrimitive -> v.content
    else -> v.toString()
}

private fun JsonObject.with(key: String, value: String) = JsonObject(this + (key to JsonPrimitive(value)))

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private fun JsonObject.date(key: String): LocalDate? = text(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * RLS refusal or a parent that isn't yours -> FORBIDDEN; bad data -> BAD_OP;
 * serialization failure / deadlock / cancelled statement -> retry later (503).
 * Anything else is a server bug and stays a 500.
 */
private fun codeFor(e: SQLException): String {
    val state = e.sqlState ?: throw e
    return when {
        state == "42501" || state == "23503" -> "FORBIDDEN"
        state == "40001" || state == "40P01" || state == "57014" -> throw app.loopdo.server.DbUnavailable(e)
        state.startsWith("23") || state.startsWith("22") || state.startsWith("42") || state == "P0001" -> "BAD_OP"
        else -> throw e
    }
}
