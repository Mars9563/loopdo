package app.loopdo.desktop

import app.loopdo.client.auth.AuthState
import app.loopdo.desktop.journal.compressPhoto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D3 exit (TEST USER): lines, an edit, a delete, the mood and a photo from the laptop reach another device and R2. */
class JournalLiveTest {
    private suspend fun start(dir: File): DesktopGraph {
        val g = DesktopGraph(dir)
        if (g.auth.session.first { it !is AuthState.Loading } !is AuthState.SignedIn) assertTrue(g.auth.signIn(LiveTestUser.email, LiveTestUser.password).isSuccess)
        withTimeout(20_000) { g.auth.session.first { it is AuthState.SignedIn } }
        withTimeout(60_000) { g.sync.db.currentStatus.asFlow().first { it.hasSynced == true } }
        withTimeout(30_000) { while (g.anchorStore.current() == null) delay(300) }
        return g
    }

    @Test
    fun `the journal round-trips through the server`() = runBlocking {
        LiveTestUser.assumeLive()
        val a = start(Files.createTempDirectory("loopdo-j1").toFile())
        val today = app.loopdo.shared.day.dateResolver(a.todayData.versionsNow())(a.trustedClock.now().instant)
        val tag = UUID.randomUUID().toString().take(6)
        a.journalActions.addLines(today, "laptop line one $tag\nlaptop line two $tag")
        val ids = a.sync.db.getAll("SELECT id, text FROM journal_lines WHERE text LIKE ? AND deleted_at IS NULL", listOf("%$tag")) { it.getString(0)!! to it.getString(1)!! }
        assertEquals(2, ids.size)
        val one = ids.first { it.second.startsWith("laptop line one") }.first
        val two = ids.first { it.second.startsWith("laptop line two") }.first
        a.journalActions.editLine(today, one, "laptop line one, edited $tag")
        a.journalActions.deleteLine(today, two)
        a.journalActions.setMood(today, 4)
        // A photo, only if today still has room (≤ 4 per day: the test may run more than once a day).
        val photos = a.sync.db.get("SELECT count(*) FROM journal_photos p JOIN journal_entries e ON e.id = p.entry_id WHERE e.loopdo_date = ?", listOf(today.toString())) { it.getLong(0)!! }
        var photoId: UUID? = null
        if (photos < 4) {
            val src = File.createTempFile("fern", ".jpg").apply { ImageIO.write(BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB), "jpg", this) }
            val id = UUID.randomUUID()
            a.journalActions.addPhoto(today, compressPhoto(src, File(a.photoDir, "$id.webp"), id)); photoId = id
            a.photoLoop.kick()
        }
        withTimeout(90_000) { while (a.sync.db.get("SELECT count(*) FROM ps_crud") { it.getLong(0)!! } > 0) delay(500) }
        if (photoId != null) withTimeout(90_000) {
            while (a.sync.db.getOptional("SELECT uploaded FROM photo_files WHERE id = ?", listOf(photoId.toString())) { it.getLong(0) ?: 0L } != 1L) delay(500)
        }

        val b = start(Files.createTempDirectory("loopdo-j2").toFile())
        withTimeout(30_000) {
            while (b.sync.db.get("SELECT count(*) FROM journal_lines WHERE text = ? AND deleted_at IS NULL", listOf("laptop line one, edited $tag")) { it.getLong(0)!! } == 0L) delay(250)
        }
        assertEquals(1L, b.sync.db.get("SELECT count(*) FROM journal_lines WHERE id = ? AND deleted_at IS NOT NULL", listOf(two)) { it.getLong(0)!! }, "the delete arrived")
        assertEquals(4L, b.sync.db.get("SELECT mood FROM journal_entries WHERE loopdo_date = ?", listOf(today.toString())) { it.getLong(0)!! })
        if (photoId != null) {
            assertEquals(1L, b.sync.db.get("SELECT count(*) FROM journal_photos WHERE id = ?", listOf(photoId.toString())) { it.getLong(0)!! })
            assertTrue(b.photoCache.file(photoId.toString(), null)!!.length() > 1000, "device b downloads the photo from R2")
        }
        listOf(a, b).forEach { it.sync.db.disconnect(); it.sync.db.close(); it.scope.cancel() }
    }
}
