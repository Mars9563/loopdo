package app.loopdo.desktop.journal

import app.loopdo.client.journal.PreparedPhoto
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Long edge of a stored photo, and the server's presign limit: the phone's values (journal/Photos.kt). */
const val PHOTO_EDGE = 2048
const val PHOTO_MAX_BYTES = 5L * 1024 * 1024

/**
 * The phone's `compress` for the laptop: at most [PHOTO_EDGE] px on the long edge (never enlarged), turned upright
 * by the JPEG's EXIF orientation (Skia applies it on decode), WebP at quality 85 (70, 55 if it would pass 5 MB). Skia writes no EXIF, so no
 * location or camera data leaves the laptop.
 */
fun compressPhoto(src: File, out: File, id: UUID = UUID.randomUUID(), maxBytes: Long = PHOTO_MAX_BYTES): PreparedPhoto {
    val bytes = src.readBytes()
    val img = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: throw IllegalArgumentException("that file isn't a photo.")
    // Skia's decoder already turns the picture upright by its EXIF orientation (checked by the tests).
    val scale = minOf(1.0, PHOTO_EDGE.toDouble() / maxOf(img.width, img.height))
    val w = (img.width * scale).roundToInt().coerceAtLeast(1)
    val h = (img.height * scale).roundToInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.MITCHELL, null, true)
    val snap = surface.makeImageSnapshot()
    out.parentFile?.mkdirs()
    for (q in listOf(85, 70, 55)) {
        out.writeBytes(snap.encodeToData(EncodedImageFormat.WEBP, q)!!.bytes)
        if (out.length() <= maxBytes) break
    }
    if (out.length() > maxBytes) { out.delete(); throw IllegalArgumentException("that photo is too big, even after shrinking.") }
    return PreparedPhoto(id, out.path, w, h, out.length().toInt())
}
