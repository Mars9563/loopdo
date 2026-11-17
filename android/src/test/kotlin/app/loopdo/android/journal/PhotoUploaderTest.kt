package app.loopdo.android.journal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

/** The photo queue (M6): a photo made offline waits on the phone and reaches R2 later; nothing is lost. */
class PhotoUploaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeApi(var presign: (UUID) -> PresignResult) : PhotoApi {
        val puts = mutableListOf<String>()
        var urls = mutableMapOf<String, String?>()
        var downloads = 0
        override suspend fun presign(photoId: UUID, entryId: String, bytes: Long) = presign(photoId)
        override suspend fun put(url: String, file: File) { puts += url }
        override suspend fun getUrl(photoId: String): String? = urls[photoId]
        override suspend fun download(url: String, out: File) { downloads++; out.writeText("image from $url") }
    }

    private fun pending(n: Int) = (1..n).map { PendingPhoto(UUID.randomUUID(), tmp.newFile().path, "entry-$it", 10) }

    @Test
    fun uploadsPendingAndMarksThem() = runBlocking {
        val queue = pending(2).toMutableList()
        val api = FakeApi { PresignResult.Ok("u/x/$it.webp", "https://r2/put/$it") }
        val marked = mutableListOf<UUID>()
        assertEquals(2, PhotoUploader(api, { queue.toList() }) { id -> marked += id; queue.removeAll { it.id == id } }.runOnce())
        assertEquals(2, api.puts.size)
        assertEquals(2, marked.size)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun forbiddenPresignIsRetriedLater() = runBlocking {
        // The entry hasn't reached the server yet: the photo stays queued, the next run uploads it.
        val queue = pending(1).toMutableList()
        var entrySynced = false
        val api = FakeApi { if (entrySynced) PresignResult.Ok("k", "https://r2/put") else PresignResult.Forbidden }
        val uploader = PhotoUploader(api, { queue.toList() }) { id -> queue.removeAll { it.id == id } }
        assertEquals(0, uploader.runOnce())
        assertEquals(1, queue.size)
        entrySynced = true
        assertEquals(1, uploader.runOnce())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun unavailableKeepsEverything() = runBlocking {
        // R2 not configured on the server, or no network: nothing is marked, nothing is deleted.
        val queue = pending(2).toMutableList()
        val api = FakeApi { PresignResult.Unavailable }
        assertEquals(0, PhotoUploader(api, { queue.toList() }) { id -> queue.removeAll { it.id == id } }.runOnce())
        assertEquals(2, queue.size)
        val offline = object : PhotoApi by api {
            override suspend fun presign(photoId: UUID, entryId: String, bytes: Long): PresignResult = throw IOException("offline")
        }
        assertEquals(0, PhotoUploader(offline, { queue.toList() }) { id -> queue.removeAll { it.id == id } }.runOnce())
        assertEquals(2, queue.size)
        assertTrue(queue.all { File(it.path).exists() })
    }

    @Test
    fun cacheDownloadsOnceThenReuses() = runBlocking {
        val api = FakeApi { PresignResult.Unavailable }.apply { urls["p1"] = "https://r2/get/p1"; urls["p2"] = null }
        val cache = PhotoCache(tmp.newFolder("cache"), api)
        val local = tmp.newFile("mine.webp").apply { writeText("taken on this phone") }
        assertEquals(local, cache.file("p0", local.path)) // this phone's own file: no download
        val f = cache.file("p1", null)!!
        assertEquals("image from https://r2/get/p1", f.readText())
        cache.file("p1", null)
        assertEquals(1, api.downloads)
        assertNull(cache.file("p2", null)) // not uploaded yet by its phone
    }
}
