package app.tel.domain.ocr

import java.math.BigDecimal

/**
 * Reads odometer, fuel level and range from a photo of the instrument cluster.
 *
 * Signals used, strongest first:
 *  - an "ODO" label next to the number; a "km"/"mi" unit right after it (but never "km/h");
 *  - a "%" sign (fuel level) or a fraction like "3/4";
 *  - "range", "DTE", "to empty" labels for range.
 * Trip meters ("TRIP", "A", "B") and speeds are ignored.
 */
object HudParser {
    private val odoWords = listOf("odo", "odometer", "total", "0d0", "oda")
    private val tripWords = listOf("trip", "tr ", "avg", "average", "km/l", "l/100", "mpg", "km/h", "kmh", "mph", "rpm", "°", "temp")
    private val rangeWords = listOf("range", "dte", "to empty", "dist", "fuel range")
    private val fuelWords = listOf("fuel", "tank")

    fun parse(lines: List<OcrLine>): ImageReading {
        if (lines.isEmpty()) return ImageReading.failed("We couldn't read this image.")
        val rawText = lines.filter { it.variant == lines.first().variant }.joinToString("\n") { it.text }

        val odometer = odometer(lines)
        val level = fuelLevel(lines)
        val range = range(lines, odometer.best?.value)
        val fields = listOf(odometer, level, range).associateBy { it.field }
        val problem = if (fields.values.all { it.best == null }) "We couldn't read the dashboard values. Enter them manually." else null
        return ImageReading(fields, rawText, problem)
    }

    private fun lc(s: String) = s.lowercase()

    private fun odometer(lines: List<OcrLine>): FieldResult {
        data class C(val token: NumToken, var score: Float, val reason: String)
        val cands = mutableListOf<C>()
        for (l in lines) {
            val text = lc(l.text)
            for (t in NumberExtractor.extract(l)) {
                if (t.synthetic) continue
                val v = t.value
                if (v < BigDecimal(10) || v >= BigDecimal(2_000_000)) continue
                val suffix = t.suffix
                if (suffix.startsWith("km/h") || suffix.startsWith("kmh") || suffix.startsWith("mph") || suffix.startsWith("%") || suffix.startsWith("rpm")) continue
                var score = 0.3f
                var reason = "Largest number on dashboard"
                if (odoWords.any { text.contains(it) }) { score += 0.55f; reason = "Next to ODO label" }
                if (suffix.startsWith("km") || suffix.startsWith("mi")) { score += 0.25f; if (reason.startsWith("Largest")) reason = "Followed by distance unit" }
                if (tripWords.any { text.contains(it) }) score -= 0.5f
                if (rangeWords.any { text.contains(it) }) score -= 0.5f
                if (v.scale() > 1) score -= 0.2f
                val digits = v.toBigInteger().toString().length
                if (digits >= 4) score += 0.1f
                if (digits <= 2) score -= 0.3f
                cands += C(t, score, reason)
            }
        }
        if (cands.isEmpty()) return FieldResult(OcrField.ODOMETER, null)
        // Values read in more than one variant are more trustworthy.
        val byValue = cands.groupBy { it.token.value.stripTrailingZeros() }
        val merged = byValue.values.map { group ->
            val top = group.maxBy { it.score }
            val variants = group.map { it.token.line.variant }.distinct().size
            top.copy(score = top.score + 0.05f * (variants - 1))
        }.sortedWith(compareByDescending<C> { it.score }.thenByDescending { it.token.value })
        val best = merged.first()
        return FieldResult(
            OcrField.ODOMETER,
            Candidate(best.token.value, best.token.raw, best.score.coerceIn(0.05f, 0.97f), best.reason),
            merged.drop(1).take(4).map { Candidate(it.token.value, it.token.raw, it.score.coerceIn(0.05f, 0.9f), "Other number in photo") },
        )
    }

    private val fraction = Regex("""\b([1-7])\s*/\s*([248])\b""")

    private fun fuelLevel(lines: List<OcrLine>): FieldResult {
        val cands = mutableListOf<Candidate>()
        for (l in lines) {
            val text = lc(l.text)
            for (t in NumberExtractor.extract(l)) {
                if (t.synthetic) continue
                val v = t.value
                if (v.signum() < 0 || v > BigDecimal(100)) continue
                if (t.suffix.startsWith("%")) {
                    var c = 0.7f
                    if (fuelWords.any { text.contains(it) }) c += 0.15f
                    cands += Candidate(v, t.raw + "%", c, "Percentage on dashboard")
                }
            }
            for (m in fraction.findAll(text)) {
                val num = m.groupValues[1].toInt(); val den = m.groupValues[2].toInt()
                if (num < den) {
                    val pct = BigDecimal(num * 100).divide(BigDecimal(den), 1, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
                    cands += Candidate(pct, m.value, if (fuelWords.any { text.contains(it) }) 0.6f else 0.4f, "Gauge fraction")
                }
            }
        }
        val dedup = cands.groupBy { it.value.stripTrailingZeros() }.values.map { g -> g.maxBy { it.confidence }.let { it.copy(confidence = (it.confidence + 0.05f * (g.size - 1)).coerceAtMost(0.95f)) } }
            .sortedByDescending { it.confidence }
        return FieldResult(OcrField.FUEL_LEVEL, dedup.firstOrNull(), dedup.drop(1).take(4))
    }

    private fun range(lines: List<OcrLine>, odometer: BigDecimal?): FieldResult {
        val cands = mutableListOf<Candidate>()
        for ((i, l) in lines.withIndex()) {
            val text = lc(l.text)
            val prev = lines.getOrNull(i - 1)?.takeIf { it.variant == l.variant }?.text?.lowercase() ?: ""
            val labelled = rangeWords.any { text.contains(it) || prev.contains(it) }
            if (!labelled) continue
            for (t in NumberExtractor.extract(l)) {
                if (t.synthetic || t.value == odometer) continue
                if (t.value < BigDecimal.ONE || t.value > BigDecimal(2000)) continue
                if (t.suffix.startsWith("km/h")) continue
                cands += Candidate(t.value, t.raw, if (t.suffix.startsWith("km") || t.suffix.startsWith("mi")) 0.85f else 0.65f, "Next to range label")
            }
        }
        val dedup = cands.distinctBy { it.value.stripTrailingZeros() }.sortedByDescending { it.confidence }
        return FieldResult(OcrField.RANGE, dedup.firstOrNull(), dedup.drop(1).take(3))
    }
}
