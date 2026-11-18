package app.loopdo.android.journal

import app.loopdo.android.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.UUID

// Photos reach R2 without the sync queue: the row syncs with PowerSync, the file goes straight to R2 through
// a short-lived signed URL from the API (/api/photos/presign), and other phones fetch it through
// /api/photos/{id}/url. The file stays on this phone until it is uploaded, so a photo taken offline is never lost.

sealed interface PresignResult {
    data class Ok(val key: String, val putUrl: String) : PresignResult
    /** The day's entry isn't on the server yet (or not this user's): try again after it syncs. */
    data object Forbidden : PresignResult
    /** R2 isn't configured on the server: keep the file, try later. */
    data object Unavailable : PresignResult
}

interface PhotoApi {
    suspend fun presign(photoId: UUID, entryId: String, bytes: Long): PresignResult
    suspend fun put(url: String, file: File)
    /** A short-lived download URL, or null if the photo isn't known to the server yet. */
    suspend fun getUrl(photoId: String): String?
    suspend fun download(url: String, out: File)
}

/** A photo whose file is on this phone and not yet in R2. */
data class PendingPhoto(val id: UUID, val path: String, val entryId: String, val bytes: Long)

/** One pass over the queue. A refusal or a network error leaves the photo queued; nothing is ever deleted here. */
class PhotoUploader(
    private val api: PhotoApi,
    private val pending: suspend () -> List<PendingPhoto>,
    /** Told about a network error (the photo stays queued); the app logs it. */
    private val onError: (IOException) -> Unit = {},
    private val markUploaded: suspend (UUID) -> Unit,
) {
    /** How many photos reached R2. */
    suspend fun runOnce(): Int {
        var done = 0
        for (p in pending()) {
            val file = File(p.path)
            if (!file.exists()) continue // deleted meanwhile: nothing to send
            try {
                when (val r = api.presign(p.id, p.entryId, p.bytes)) {
                    is PresignResult.Ok -> { api.put(r.putUrl, file); markUploaded(p.id); done++ }
                    PresignResult.Forbidden, PresignResult.Unavailable -> Unit
                }
            } catch (e: IOException) {
                onError(e)
            }
        }
        return done
    }
}

/**
 * Where a photo is read from: this phone's own file if it has one, else a cached download (Android may clear
 * the cache; it is fetched again). Null if the photo hasn't reached R2 yet.
 */
class PhotoCache(private val dir: File, private val api: PhotoApi) {
    suspend fun file(photoId: String, localPath: String?): File? {
        localPath?.let { File(it) }?.takeIf { it.exists() }?.let { return it }
        val cached = File(dir, "$photoId.webp")
        if (cached.exists() && cached.length() > 0) return cached
        val url = try { api.getUrl(photoId) } catch (e: IOException) { null } ?: return null
        dir.mkdirs()
        val part = File(dir, "$photoId.${java.util.UUID.randomUUID()}.part") // two loads of one photo never share a file
        return try {
            api.download(url, part)
            if (part.length() == 0L) null else cached.also { part.renameTo(it) }
        } catch (e: IOException) {
            part.delete(); null
        }
    }
}

/** The API's photo routes (server PhotoRoutes.kt), with the signed-in user's token. */
class KtorPhotoApi(private val http: HttpClient, private val token: suspend () -> String?) : PhotoApi {
    private suspend fun bearer() = token() ?: throw IOException("not signed in")

    override suspend fun presign(photoId: UUID, entryId: String, bytes: Long): PresignResult {
        val r = try {
            http.post("${AppConfig.apiUrl}/api/photos/presign") {
                bearerAuth(bearer())
                contentType(ContentType.Application.Json)
                setBody("""{"photoId":"$photoId","entryId":"$entryId","contentType":"image/webp","bytes":$bytes}""")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw IOException(e.message, e) }
        return when (r.status.value) {
            200 -> Json.parseToJsonElement(r.bodyAsText()).jsonObject.let {
                PresignResult.Ok(it["key"]!!.jsonPrimitive.content, it["putUrl"]!!.jsonPrimitive.content)
            }
            422 -> PresignResult.Forbidden
            503 -> PresignResult.Unavailable
            else -> throw IOException("presign answered ${r.status.value}")
        }
    }

    override suspend fun put(url: String, file: File) {
        val r = try {
            http.put(url) {
                contentType(ContentType("image", "webp")) // the signature covers the type and the length
                setBody(file.readBytes())
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw IOException(e.message, e) }
        if (r.status.value !in 200..299) throw IOException("R2 answered ${r.status.value}")
    }

    override suspend fun getUrl(photoId: String): String? {
        val r = try {
            http.get("${AppConfig.apiUrl}/api/photos/$photoId/url") { bearerAuth(bearer()) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw IOException(e.message, e) }
        return when (r.status.value) {
            200 -> Json.parseToJsonElement(r.bodyAsText()).jsonObject["getUrl"]!!.jsonPrimitive.content
            404, 503 -> null
            else -> throw IOException("photo url answered ${r.status.value}")
        }
    }

    override suspend fun download(url: String, out: File) {
        val r = try { http.get(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw IOException(e.message, e) }
        if (r.status.value != 200) throw IOException("R2 answered ${r.status.value}")
        out.writeBytes(r.bodyAsBytes())
    }
}
