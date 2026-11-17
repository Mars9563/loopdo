package app.loopdo.android.journal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Runs on the phone: a camera-sized photo becomes a ≤ 2048 px WebP under the server's 5 MB limit (spec §6.6). */
@RunWith(AndroidJUnit4::class)
class PhotoCompressTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "compress-test").apply { mkdirs() }

    @Test
    fun compressKeepsAspectAndLimit() {
        val big = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        Canvas(big).apply {
            drawColor(Color.rgb(40, 60, 90))
            val p = Paint()
            for (i in 0 until 200) { p.color = Color.rgb(i % 255, (i * 7) % 255, (i * 13) % 255); drawCircle((i * 19 % 4000).toFloat(), (i * 23 % 3000).toFloat(), 120f, p) }
        }
        val src = File(dir, "src.jpg").apply { outputStream().use { big.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val id = UUID.randomUUID()
        val out = File(dir, "$id.webp")
        val p = compress(ImageDecoder.createSource(src), out, id)
        assertEquals(listOf(2048, 1536), listOf(p.width, p.height))
        assertEquals(id, p.id)
        assertEquals(out.path, p.path)
        assertEquals(out.length().toInt(), p.bytes)
        assertTrue("under the server's 5 MB", p.bytes in 1..5 * 1024 * 1024)
        val head = out.readBytes().copyOfRange(0, 12)
        assertEquals("RIFF", String(head, 0, 4))
        assertEquals("WEBP", String(head, 8, 4))
        // A small photo is not enlarged.
        val small = File(dir, "small.png").apply { outputStream().use { Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val q = compress(ImageDecoder.createSource(small), File(dir, "small.webp"))
        assertEquals(listOf(800, 600), listOf(q.width, q.height))
    }
}
