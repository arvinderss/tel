package app.tel.domain.ocr

import java.math.BigDecimal

/** Axis-aligned box in image pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val height get() = bottom - top
    val width get() = right - left

    fun horizontalOverlap(o: Box): Int = (minOf(right, o.right) - maxOf(left, o.left)).coerceAtLeast(0)
}

/**
 * One line of text recognised by the OCR engine on one preprocessed variant of an image.
 * The parsers are pure functions over these lines, so they can be unit-tested without Android.
 */
data class OcrLine(
    val text: String,
    val box: Box? = null,
    /** Which preprocessing variant produced the line, e.g. "original", "contrast", "binary". */
    val variant: String = "original",
    /** Reading-order index within its variant (top-to-bottom, left-to-right). */
    val order: Int = 0,
)

enum class OcrField(val key: String, val label: String) {
    ODOMETER("odometer", "Odometer"),
    FUEL_LEVEL("fuel_level", "Fuel level"),
    RANGE("range", "Range"),
    FUEL_QUANTITY("fuel_quantity", "Fuel added"),
    PRICE_PER_UNIT("price_per_unit", "Price"),
    TOTAL_AMOUNT("total_amount", "Total"),
}

data class Candidate(
    val value: BigDecimal,
    /** The text as the OCR engine read it. */
    val raw: String,
    /** 0..1, our own estimate (agreement across variants, labels, arithmetic checks). */
    val confidence: Float,
    val reason: String,
)

data class FieldResult(
    val field: OcrField,
    val best: Candidate?,
    val alternatives: List<Candidate> = emptyList(),
) {
    val needsReview: Boolean get() = best == null || best.confidence < REVIEW_THRESHOLD

    companion object {
        const val REVIEW_THRESHOLD = 0.75f
    }
}

data class ImageReading(
    val fields: Map<OcrField, FieldResult>,
    /** All raw text read from the image, for debugging / transparency in the detail screen. */
    val rawText: String,
    /** Set when nothing usable was read (blurry, dark, wrong photo). */
    val problem: String? = null,
) {
    operator fun get(f: OcrField): FieldResult? = fields[f]

    companion object {
        fun failed(message: String) = ImageReading(emptyMap(), "", message)
    }
}
