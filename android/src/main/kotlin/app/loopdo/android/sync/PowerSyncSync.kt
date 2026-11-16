package app.loopdo.android.sync

import app.loopdo.android.auth.AuthRepository
import app.loopdo.android.auth.AuthState
import app.loopdo.android.config.AppConfig
import com.powersync.PowerSyncDatabase
import com.powersync.connectors.PowerSyncBackendConnector
import com.powersync.connectors.PowerSyncCredentials
import com.powersync.db.crud.CrudTransaction
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** A PowerSync CrudTransaction seen as one PendingTransaction. */
class PowerSyncTransaction(private val tx: CrudTransaction) : PendingTransaction {
    override val id: Int = tx.transactionId ?: tx.crud.first().clientId
    override val ops: List<PendingOp> = tx.crud.map { PendingOp(it.op.toJson(), it.table, it.id, it.opData?.toMap()) }
    override suspend fun complete() = tx.complete(null)
}

class PowerSyncDraftStore(private val db: PowerSyncDatabase) : DraftStore {
    override suspend fun save(kind: String, payload: String, reason: String) {
        db.execute(
            "INSERT INTO drafts (id, kind, payload, reason, created_at) VALUES (?, ?, ?, ?, ?)",
            listOf(UUID.randomUUID().toString(), kind, payload, reason, Instant.now().toString()),
        )
    }
}

class PowerSyncUploadState(private val db: PowerSyncDatabase) : UploadState {
    override suspend fun firstFailure(txId: Int): Instant? =
        db.getOptional("SELECT first_failure_at FROM upload_state WHERE tx_id = ?", listOf(txId)) { Instant.parse(it.getString(0)!!) }

    override suspend fun markFailure(txId: Int, at: Instant) {
        if (firstFailure(txId) == null) {
            db.execute("INSERT INTO upload_state (id, tx_id, first_failure_at) VALUES (?, ?, ?)", listOf(UUID.randomUUID().toString(), txId, at.toString()))
        }
    }

    override suspend fun clear(txId: Int) {
        db.execute("DELETE FROM upload_state WHERE tx_id = ?", listOf(txId))
    }
}

/** POST /api/sync/upload with the signed-in user's token. Network failures surface as IOException. */
class KtorUploadApi(private val http: HttpClient, private val token: suspend () -> String?) : UploadApi {
    override suspend fun upload(body: JsonObject): HttpResult {
        val t = token() ?: throw IOException("not signed in")
        val r = try {
            http.post("${AppConfig.apiUrl}/api/sync/upload") {
                bearerAuth(t)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) { // Ktor timeouts etc.
            throw IOException(e.message, e)
        }
        return HttpResult(r.status.value, r.bodyAsText())
    }
}

/** PowerSync ↔ LoopDo: downloads with the Supabase token; uploads one transaction at a time to our API. */
class LoopDoConnector(private val auth: AuthRepository, private val uploader: Uploader) : PowerSyncBackendConnector() {
    override suspend fun fetchCredentials(): PowerSyncCredentials? =
        (auth.session.value as? AuthState.SignedIn)?.let { s ->
            PowerSyncCredentials(AppConfig.powerSyncUrl, auth.freshToken() ?: s.accessToken, s.userId.toString())
        }

    override suspend fun uploadData(database: PowerSyncDatabase) {
        while (true) {
            val tx = database.getNextCrudTransaction() ?: return
            uploader.upload(PowerSyncTransaction(tx)) // throws RetryLater → PowerSync retries later
        }
    }
}
