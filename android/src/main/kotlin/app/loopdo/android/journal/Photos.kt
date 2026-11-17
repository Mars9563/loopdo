package app.loopdo.android.journal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.loopdo.android.LoopDoApp
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Long edge of a stored photo (spec §6.6). */
const val PHOTO_EDGE = 2048
/** The server's presign limit (PhotoRoutes MAX_PHOTO_BYTES). */
const val PHOTO_MAX_BYTES = 5L * 1024 * 1024

/**
 * A photo as the journal keeps it: at most [PHOTO_EDGE] px on the long edge (never enlarged), turned upright
 * (ImageDecoder applies the EXIF orientation), WebP at quality 85 (lower if it would pass the 5 MB limit).
 */
fun compress(src: ImageDecoder.Source, out: File, id: UUID = UUID.randomUUID()): PreparedPhoto {
    val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = minOf(1.0, PHOTO_EDGE.toDouble() / maxOf(w, h))
        decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    out.parentFile?.mkdirs()
    for (quality in listOf(85, 70, 55)) {
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, it) }
        if (out.length() <= PHOTO_MAX_BYTES) break
    }
    return PreparedPhoto(id, out.path, bmp.width, bmp.height, out.length().toInt()).also { bmp.recycle() }
}

/** Where this phone keeps its own photo files until (and after) they reach R2. */
fun photoFile(context: Context, id: UUID): File = File(File(context.filesDir, "photos"), "$id.webp")

/** Sends queued photos when there is a network; WorkManager retries it later while some are left. */
class PhotoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LoopDoApp).graph
        graph.photoUploader.runOnce()
        return if (graph.pendingPhotoCount() > 0) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PhotoWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("loopdo-photos", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
