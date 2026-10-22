package app.tel.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.tel.domain.ocr.Box
import app.tel.domain.ocr.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR using ML Kit's bundled Latin text model (shipped inside the APK; works offline).
 *
 * Each photo is recognised several times with different preprocessing, because LCD and
 * seven-segment displays respond differently to each:
 *   original  – the photo as taken (orientation already corrected on import)
 *   contrast  – grayscale, noise reduction, percentile contrast stretch, sharpen
 *   binary    – Otsu threshold of the contrast image (dark text on light)
 *   inverted  – Otsu threshold, inverted (for light-on-dark dashboards)
 *   segments  – binary with segment gaps closed (seven-segment digits)
 * The parsers merge the results and give extra confidence to numbers seen in more than one.
 */
class MlKitTextReader : TextReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun read(file: File): ReadResult = withContext(Dispatchers.Default) {
        val bitmap = decode(file, 1600) ?: return@withContext ReadResult(emptyList(), 0.0, 0.0)
        val w = bitmap.width; val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)

        val gray = ImageOps.grayscale(argb)
        val sharp = ImageOps.sharpness(gray, w, h)
        val bright = ImageOps.mean(gray)
        val contrast = ImageOps.sharpen(ImageOps.stretchContrast(ImageOps.boxBlur(gray, w, h)), w, h)
        val t = ImageOps.otsuThreshold(contrast)
        val binary = ImageOps.threshold(contrast, t)
        val inverted = ImageOps.threshold(contrast, t, invert = true)
        // Whichever polarity has less "ink" is most likely the text layer.
        val darkInk = if (binary.count { it == 0 } <= inverted.count { it == 0 }) binary else inverted
        val segments = ImageOps.dilateDark(darkInk, w, h, (minOf(w, h) / 400).coerceIn(1, 4))

        val variants = listOf(
            "original" to bitmap,
            "contrast" to grayBitmap(contrast, w, h),
            "binary" to grayBitmap(binary, w, h),
            "inverted" to grayBitmap(inverted, w, h),
            "segments" to grayBitmap(segments, w, h),
        )
        val lines = mutableListOf<OcrLine>()
        for ((name, bmp) in variants) {
            val text = runCatching { recognize(bmp) }.getOrNull() ?: continue
            lines += toLines(text, name)
        }
        variants.forEach { (_, b) -> if (!b.isRecycled) b.recycle() }
        ReadResult(lines, sharp, bright)
    }

    private fun toLines(text: Text, variant: String): List<OcrLine> {
        val raw = text.textBlocks.flatMap { it.lines }.map { l ->
            val r = l.boundingBox
            OcrLine(l.text, r?.let { Box(it.left, it.top, it.right, it.bottom) }, variant)
        }
        // Reading order: top-to-bottom, then left-to-right (rows grouped by half a line height).
        val heights = raw.mapNotNull { it.box?.height }.sorted()
        val rowHeight = maxOf(1, (heights.getOrNull(heights.size / 2) ?: 1) / 2)
        val sorted = raw.sortedWith(compareBy<OcrLine>({ (it.box?.centerY ?: 0) / rowHeight }, { it.box?.left ?: 0 }))
        return sorted.mapIndexed { i, l -> l.copy(order = i) }
    }

    private suspend fun recognize(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun grayBitmap(gray: IntArray, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(ImageOps.toArgb(gray), w, h, Bitmap.Config.ARGB_8888)

    private fun decode(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val s = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }
}
