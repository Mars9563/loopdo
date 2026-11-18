package app.loopdo.desktop

import app.loopdo.desktop.journal.compressPhoto
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** D3: laptop photos come out like the phone's: ≤ 2048 px long edge, upright, WebP, ≤ 5 MB. */
class DesktopPhotosTest {
    private val dir: File = Files.createTempDirectory("loopdo-photos").toFile()

    private fun jpeg(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = java.awt.Color(40, 120, 80); g.fillRect(0, 0, w, h / 2); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    /** Inserts an APP1 Exif segment with Orientation = [o] right after the JPEG's SOI marker. */
    private fun withOrientation(jpg: ByteArray, o: Int): ByteArray {
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, o.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
        return jpg.copyOfRange(0, 2) + app1 + jpg.copyOfRange(2, jpg.size)
    }

    @Test
    fun `a big photo is scaled to 2048 on the long edge and stored as webp`() {
        val src = File(dir, "big.jpg").apply { writeBytes(jpeg(4000, 3000)) }
        val p = compressPhoto(src, File(dir, "big.webp"))
        assertEquals(2048, maxOf(p.width, p.height))
        assertEquals(1536, minOf(p.width, p.height))
        val head = File(p.path).readBytes().copyOfRange(0, 12)
        assertEquals("RIFF", String(head, 0, 4)); assertEquals("WEBP", String(head, 8, 4))
        assertTrue(File(p.path).length() <= 5L * 1024 * 1024)
        assertEquals(File(p.path).length().toInt(), p.bytes)
    }

    @Test
    fun `a small photo is never enlarged`() {
        val p = compressPhoto(File(dir, "small.jpg").apply { writeBytes(jpeg(640, 480)) }, File(dir, "small.webp"))
        assertEquals(640, p.width); assertEquals(480, p.height)
    }

    @Test
    fun `an exif-rotated phone photo is stored upright`() {
        val bytes = withOrientation(jpeg(300, 200), 6)
        val p = compressPhoto(File(dir, "rot.jpg").apply { writeBytes(bytes) }, File(dir, "rot.webp"))
        assertEquals(200, p.width); assertEquals(300, p.height)
    }

    @Test
    fun `a file that isn't a photo is refused`() {
        val e = assertFailsWith<IllegalArgumentException> { compressPhoto(File(dir, "notes.txt").apply { writeText("hello") }, File(dir, "x.webp")) }
        assertEquals("that file isn't a photo.", e.message)
    }
}
