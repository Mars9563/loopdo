package app.loopdo.android.sync

import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** One queued local change (a PowerSync CrudEntry, decoupled from the SDK for testing). */
data class PendingOp(val op: String, val table: String, val id: String, val data: Map<String, Any?>?)

/** One local transaction = one user action; uploaded and acknowledged as a unit. */
interface PendingTransaction {
    val id: Int
    val ops: List<PendingOp>
    suspend fun complete()
}

data class HttpResult(val status: Int, val body: String?)

/** POST /api/sync/upload. Throws IOException when the network fails. */
interface UploadApi {
    suspend fun upload(body: JsonObject): HttpResult
}

data class Draft(val kind: String, val payload: String, val reason: String)

/** Text the user typed that the server refused: kept, never silently discarded. */
interface DraftStore {
    suspend fun save(kind: String, payload: String, reason: String)
}

/** When a transaction first failed with a server error (to set it aside after a day). */
interface UploadState {
    suspend fun firstFailure(txId: Int): Instant?
    suspend fun markFailure(txId: Int, at: Instant)
    suspend fun clear(txId: Int)
}

sealed interface UploadOutcome {
    data object Applied : UploadOutcome
    data class Rejected(val code: String, val opIndex: Int) : UploadOutcome
    data object RetryLater : UploadOutcome
    data object ServerBug : UploadOutcome
}

/** Thrown from uploadData so PowerSync keeps the transaction and retries with backoff. */
class RetryLater(reason: String) : IOException(reason)

/** The API contract (ROADMAP "Contract for the Android app"). */
object UploadClassifier {
    fun classify(status: Int, body: String?): UploadOutcome = when (status) {
        200 -> UploadOutcome.Applied
        422 -> runCatching {
            val o = Json.parseToJsonElement(body!!).jsonObject
            val code = o.getValue("code").jsonPrimitive.content
            if (code == "SETTINGS_REQUIRED") UploadOutcome.RetryLater else UploadOutcome.Rejected(code, o.getValue("opIndex").jsonPrimitive.int)
        }.getOrDefault(UploadOutcome.ServerBug)
        401, 408, 429, 502, 503, 504 -> UploadOutcome.RetryLater
        else -> if (status >= 500) UploadOutcome.ServerBug else UploadOutcome.RetryLater
    }
}

/** Local rows → the JSON the API expects. */
object OpMapper {
    /** Columns stored locally as JSON text that the API needs as real JSON. */
    private val jsonColumns = mapOf(
        "habit_logs" to setOf("evidence"),
        "tasks" to setOf("evidence", "repeat_rule"),
        "profiles" to setOf("settings"),
        "habit_schedules" to setOf("weekdays"),
    )

    /** Decided by the server (from the token or its own rules); never sent, or the API refuses the op. */
    private val serverOwned = setOf("user_id", "verified", "completed_on", "created_at", "updated_at")

    fun toJson(op: PendingOp): JsonObject = buildJsonObject {
        put("op", op.op)
        put("table", op.table)
        put("id", op.id)
        op.data?.let { data ->
            put("data", JsonObject(data.filterKeys { it !in serverOwned }.mapValues { (col, v) -> value(op.table, col, v) }))
        }
    }

    private fun value(table: String, col: String, v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> if (col in jsonColumns[table].orEmpty()) Json.parseToJsonElement(v) else JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    fun batch(tx: PendingTransaction): JsonObject = buildJsonObject {
        putJsonArray("ops") { tx.ops.forEach { add(toJson(it)) } }
    }

    /** Fields the user typed by hand, per table: what a refusal must not throw away. */
    val typedText: Map<String, List<String>> = mapOf(
        "journal_entries" to listOf("text"),
        "tasks" to listOf("title", "notes"),
        "subtasks" to listOf("title"),
        "lists" to listOf("name"),
        "habits" to listOf("title"),
    )
}

/**
 * Uploads ONE transaction and decides its fate (contract):
 * applied → complete; 422 → keep typed text as drafts, then complete (discard);
 * 503/network → retry; 500 → retry, and after 24 h set it aside like a refusal.
 */
/** A transaction the server refused and the phone undid; [keptDraft] = typed text was saved as a draft. */
data class SetAside(val code: String, val keptDraft: Boolean)

/** What the snackbar says (spec: plain-language explanation of a rejected upload). */
fun SetAside.message(): String {
    val what = when (code) {
        "INVALID_EVIDENCE" -> "the server couldn't confirm when that tick happened, so it was undone."
        "BACKDATED" -> "that change was for a day that had already ended, so it was undone."
        "FUTURE" -> "that tick was dated in the future, so it was undone."
        "BEFORE_LAST_ANCHOR" -> "that tick was older than the phone's last time check, so it was undone."
        "SERVER_ERROR" -> "the server kept failing on a change for a day, so it was undone."
        else -> "the server refused a change ($code), so it was undone."
    }
    return if (keptDraft) "$what your text is saved as a draft." else what
}

/**
 * Set-aside notices waiting to be shown. Kept until the today screen shows them, so a refusal
 * that happens while nobody is looking is still explained later (not dropped).
 */
class SetAsideNotices {
    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<List<SetAside>>(emptyList())
    val pending: kotlinx.coroutines.flow.StateFlow<List<SetAside>> = _pending
    fun add(n: SetAside) = _pending.update { it + n }
    fun consume(shown: List<SetAside>) = _pending.update { it.drop(shown.size) }
}

class Uploader(
    private val api: UploadApi,
    private val drafts: DraftStore,
    private val state: UploadState,
    private val onSetAside: (SetAside) -> Unit = {},
    private val clock: () -> Instant,
) {
    suspend fun upload(tx: PendingTransaction) {
        val result = try {
            api.upload(OpMapper.batch(tx))
        } catch (e: IOException) {
            throw RetryLater("network: ${e.message}")
        }
        when (val outcome = UploadClassifier.classify(result.status, result.body)) {
            UploadOutcome.Applied -> { state.clear(tx.id); tx.complete() }
            is UploadOutcome.Rejected -> setAside(tx, outcome.code)
            UploadOutcome.RetryLater -> throw RetryLater("server said retry (${result.status})")
            UploadOutcome.ServerBug -> {
                val first = state.firstFailure(tx.id) ?: clock().also { state.markFailure(tx.id, it) }
                if (Duration.between(first, clock()) < SERVER_BUG_PATIENCE) throw RetryLater("server error ${result.status}")
                setAside(tx, "SERVER_ERROR")
            }
        }
    }

    private suspend fun setAside(tx: PendingTransaction, reason: String) {
        var kept = false
        for (op in tx.ops) {
            val fields = OpMapper.typedText[op.table] ?: continue
            for (f in fields) {
                val text = op.data?.get(f) as? String ?: continue
                if (text.isNotBlank()) { drafts.save("${op.table}.$f", text, reason); kept = true }
            }
        }
        // The whole transaction too (ticks have no typed text): nothing vanishes without a trace.
        drafts.save("transaction", OpMapper.batch(tx).toString(), reason)
        state.clear(tx.id)
        tx.complete() // the next sync checkpoint then restores the server's version locally
        onSetAside(SetAside(reason, kept))
    }

    companion object {
        val SERVER_BUG_PATIENCE: Duration = Duration.ofHours(24)
    }
}
