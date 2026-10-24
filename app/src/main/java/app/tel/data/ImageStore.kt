package app.tel.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Stores photos privately inside the app:
 *
 *   files/data/vehicles/<vehicle-id>/transactions/<transaction-id>/photo-<id>.jpg
 *
 * Every stored photo is a fresh JPEG re-encoded from pixels, so EXIF metadata from the camera
 * (GPS location, device serials, timestamps) is not kept. Orientation is applied to the pixels first.
 */
class ImageStore(private val context: Context) {
    private val filesRoot: File get() = context.filesDir
    private val draftsRoot: File get() = File(context.cacheDir, "drafts")

    fun fileFor(relativePath: String): File = File(filesRoot, relativePath)

    fun relativeDir(vehicleId: String, eventId: String) = "data/vehicles/$vehicleId/transactions/$eventId"

    fun newCameraFile(): File {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        return File(dir, "capture-${UUID.randomUUID()}.jpg")
    }

    /** Copies a picked/captured image into the draft folder, upright, downscaled, metadata-free. */
    suspend fun importToDraft(uri: Uri, draftId: String): File = withContext(Dispatchers.IO) {
        val dir = File(draftsRoot, draftId).apply { mkdirs() }
        val out = File(dir, "photo-${UUID.randomUUID()}.jpg")
        val bitmap = decodeUpright(uri) ?: error("Unreadable image")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        out
    }

    /** Moves a draft photo into its permanent location; returns the path relative to files/. */
    suspend fun commit(draftFile: File, vehicleId: String, eventId: String): String = withContext(Dispatchers.IO) {
        val rel = "${relativeDir(vehicleId, eventId)}/${draftFile.name}"
        val dest = fileFor(rel)
        dest.parentFile?.mkdirs()
        if (draftFile.absolutePath != dest.absolutePath) {
            draftFile.copyTo(dest, overwrite = true)
        }
        rel
    }

    suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) { fileFor(relativePath).delete() }

    suspend fun deleteEventDir(vehicleId: String, eventId: String) = withContext(Dispatchers.IO) {
        fileFor(relativeDir(vehicleId, eventId)).deleteRecursively()
    }

    suspend fun deleteVehicleDir(vehicleId: String) = withContext(Dispatchers.IO) {
        fileFor("data/vehicles/$vehicleId").deleteRecursively()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        fileFor("data").deleteRecursively()
        draftsRoot.deleteRecursively()
        File(context.cacheDir, "camera").deleteRecursively()
    }

    fun clearDraft(draftId: String) { File(draftsRoot, draftId).deleteRecursively() }

    /** Decodes with subsampling so huge camera photos don't exhaust memory, then rotates upright. */
    private fun decodeUpright(uri: Uri, maxSide: Int = 2400): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val longest = maxOf(decoded.width, decoded.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            m.postScale(s, s)
        }
        if (m.isIdentity) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }
}
