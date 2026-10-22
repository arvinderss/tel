package app.tel.ocr

import app.tel.domain.model.ImageType
import app.tel.domain.ocr.HudParser
import app.tel.domain.ocr.ImageReading
import app.tel.domain.ocr.PumpMeterParser
import java.io.File

/**
 * Image -> preprocessing -> OCR -> field extraction -> confidence.
 * Never throws: any failure becomes an [ImageReading] with a friendly [ImageReading.problem].
 */
class OcrService(private val reader: TextReader) {

    suspend fun process(type: ImageType, file: File): ImageReading {
        val result = try {
            reader.read(file)
        } catch (t: Throwable) {
            return ImageReading.failed("We couldn't read this image. Enter the values manually.")
        }
        val reading = when (type) {
            ImageType.BEFORE_HUD, ImageType.AFTER_HUD -> HudParser.parse(result.lines)
            ImageType.PUMP_METER -> PumpMeterParser.parse(result.lines)
        }
        val hint = when {
            result.lines.isEmpty() && result.brightness < 40 -> "The photo looks too dark to read. You can retake it or enter the values manually."
            result.sharpness < BLUR_THRESHOLD -> "The image may be difficult to read. You can retake it or enter the values manually."
            else -> null
        }
        val nothingFound = reading.fields.values.all { it.best == null }
        return when {
            nothingFound -> reading.copy(problem = hint ?: reading.problem ?: "We couldn't read this image. Enter the values manually.")
            hint != null && reading.fields.values.any { it.needsReview } -> reading.copy(problem = hint)
            else -> reading
        }
    }

    companion object {
        /** Laplacian variance below this (on a ~1600 px image) means a badly blurred photo. Sharp sample photos score 37–330; a 4 px blur scores ~2. */
        const val BLUR_THRESHOLD = 12.0
    }
}
