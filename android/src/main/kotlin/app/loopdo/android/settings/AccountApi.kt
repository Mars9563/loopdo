package app.loopdo.android.settings

import app.loopdo.android.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException

/** M9: the API's account routes (server AccountRoutes.kt). Each needs the network; failures are plain words. */
interface AccountApi {
    suspend fun delete()
    /** Throws [IllegalStateException] once the wipe has started (keep is refused). */
    suspend fun keep()
    suspend fun signups(): Boolean
    suspend fun setSignups(open: Boolean)
}

class KtorAccountApi(private val http: HttpClient, private val token: suspend () -> String?) : AccountApi {
    private suspend fun call(path: String, body: String? = null, get: Boolean = false): HttpResponse {
        val t = token() ?: throw IOException("not signed in")
        return try {
            if (get) http.get("${AppConfig.apiUrl}$path") { bearerAuth(t) }
            else http.post("${AppConfig.apiUrl}$path") { bearerAuth(t); if (body != null) { contentType(ContentType.Application.Json); setBody(body) } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw IOException("no connection. try again when you're online.", e) }
    }

    private suspend fun ok(r: HttpResponse) {
        when (r.status.value) {
            200 -> Unit
            409 -> throw IllegalStateException("this account is already being deleted.")
            403 -> throw IllegalStateException("only the admin can change that.")
            else -> throw IOException("the server answered ${r.status.value}. try again later.")
        }
    }

    override suspend fun delete() = ok(call("/api/account/delete"))
    override suspend fun keep() = ok(call("/api/account/keep"))
    override suspend fun signups(): Boolean = call("/api/admin/signups", get = true).also { ok(it) }.bodyAsText().contains("\"enabled\":true")
    override suspend fun setSignups(open: Boolean) = ok(call("/api/admin/signups", """{"enabled":$open}"""))
}
