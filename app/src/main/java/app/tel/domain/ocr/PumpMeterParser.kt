package app.tel.domain.ocr

import app.tel.domain.validation.Validator
import java.math.BigDecimal
import java.math.MathContext

/**
 * Reads total amount, quantity and unit price from a fuel-dispenser display.
 *
 * It makes no assumption about the layout. Instead it:
 *  1. collects every number from every OCR variant;
 *  2. scores each number against nearby labels ("Amount", "₹", "Litres", "Rate", "₹/L", "Density"…);
 *  3. searches for the (quantity, price, total) triple that satisfies quantity × price ≈ total,
 *     which is true on every dispenser and is the strongest signal available;
 *  4. falls back to labels alone when no consistent triple exists, and then flags for review.
 */
object PumpMeterParser {
    private val mc = MathContext.DECIMAL64

    enum class Role { TOTAL, QUANTITY, PRICE, OTHER }

    private val totalWords = listOf("amount", "amt", "sale", "total", "rs", "₹", "inr", "$", "€", "£", "money", "value", "cost", "dollars", "rupees", "paid")
    private val qtyWords = listOf("litre", "liter", "ltr", "litres", "liters", "volume", "vol", "qty", "quantity", "gallon", "gal", "kg", "l)", "(l", "lts")
    private val priceWords = listOf("rate", "price", "/l", "/lit", "/ltr", "/gal", "per", "unit", "/kg", "ppu")
    private val otherWords = listOf("density", "kg/m", "temp", "°", "nozzle", "pump no", "totaliser", "totalizer", "preset")

    data class Scored(val token: NumToken, val hints: Map<Role, Float>, val seenInVariants: Int)

    fun parse(lines: List<OcrLine>): ImageReading {
        if (lines.isEmpty()) return ImageReading.failed("We couldn't read this image.")
        val rawText = lines.filter { it.variant == lines.first().variant }.joinToString("\n") { it.text }

        val tokens = lines.flatMap { NumberExtractor.extract(it) }
            .filter { it.value.signum() > 0 }
            .filter { it.value < BigDecimal(10_000_000) }
        if (tokens.isEmpty()) return ImageReading(emptyMap(), rawText, "We couldn't find any numbers in this image.")

        val labels = lines.filter { l -> NumberExtractor.extract(l).isEmpty() || hasLabelWord(l.text) }
        val layout = lines.groupBy { it.variant }.mapValues { (_, ls) -> labelsBelowNumbers(ls) }
        val scored = dedupe(tokens.flatMap { t -> listOf(t) + NumberExtractor.withInsertedDecimal(t) }, labels, layout)

        val triple = bestTriple(scored)
        val fields = if (triple != null) fromTriple(triple, scored) else fromLabels(scored)
        return ImageReading(fields, rawText)
    }

    /**
     * Displays print labels either above or below their numbers. Without geometry we infer it from
     * reading order: if the first label comes after the first number, labels sit below.
     */
    private fun labelsBelowNumbers(lines: List<OcrLine>): Boolean {
        val firstLabel = lines.filter { roleOf(it.text) != null && NumberExtractor.extract(it).isEmpty() }.minOfOrNull { it.order }
            ?: return true
        val firstNumber = lines.filter { NumberExtractor.extract(it).isNotEmpty() && roleOf(it.text) == null }.minOfOrNull { it.order }
            ?: return true
        return firstLabel > firstNumber
    }

    private fun hasLabelWord(text: String): Boolean {
        val t = text.lowercase()
        return (totalWords + qtyWords + priceWords + otherWords).any { t.contains(it) }
    }

    /** Merge identical values seen in several variants; attach label hints. */
    private fun dedupe(tokens: List<NumToken>, labels: List<OcrLine>, layout: Map<String, Boolean>): List<Scored> {
        val byValue = tokens.groupBy { it.value.stripTrailingZeros() to it.decimals }
        return byValue.values.map { group ->
            val best = group.firstOrNull { !it.synthetic } ?: group.first()
            val variants = group.filter { !it.synthetic }.map { it.line.variant }.distinct().size
            val hints = HashMap<Role, Float>()
            for (t in group.filter { it.synthetic == best.synthetic }) {
                for ((role, w) in hintsFor(t, labels, layout)) hints[role] = maxOf(hints[role] ?: 0f, w)
            }
            Scored(best, hints, variants)
        }
    }

    private fun roleOf(text: String): Role? {
        val t = text.lowercase()
        return when {
            otherWords.any { t.contains(it) } -> Role.OTHER
            priceWords.any { t.contains(it) } -> Role.PRICE
            qtyWords.any { w -> Regex("""(^|[^a-z])${Regex.escape(w)}([^a-z]|$)""").containsMatchIn(t) } -> Role.QUANTITY
            totalWords.any { t.contains(it) } -> Role.TOTAL
            else -> null
        }
    }

    /** Label evidence: words on the number's own line, then the closest label line above/below. */
    private fun hintsFor(t: NumToken, labels: List<OcrLine>, layout: Map<String, Boolean>): Map<Role, Float> {
        val out = HashMap<Role, Float>()
        roleOf(t.prefix + " " + t.suffix)?.let { out[it] = 1.0f }
        roleOf(t.line.text.replace(t.raw, " "))?.let { out[it] = maxOf(out[it] ?: 0f, 0.8f) }

        val box = t.line.box
        val sameVariant = labels.filter { it.variant == t.line.variant && it !== t.line }
        if (box != null) {
            // Nearest label (vertically) that overlaps horizontally, within ~2 line-heights.
            val near = sameVariant.mapNotNull { l ->
                val lb = l.box ?: return@mapNotNull null
                val role = roleOf(l.text) ?: return@mapNotNull null
                val overlap = box.horizontalOverlap(lb)
                if (overlap <= 0 && kotlin.math.abs(lb.centerX - box.centerX) > box.width) return@mapNotNull null
                val gap = if (lb.centerY > box.centerY) lb.top - box.bottom else box.top - lb.bottom
                Triple(role, gap.coerceAtLeast(0), l)
            }.filter { it.second < box.height * 2 + 40 }.sortedBy { it.second }
            near.take(2).forEachIndexed { i, (role, _, _) ->
                val w = if (i == 0) 0.6f else 0.3f
                out[role] = maxOf(out[role] ?: 0f, w)
            }
        } else {
            // No geometry (e.g. tests): use the adjacent lines in reading order.
            val labelsBelow = layout[t.line.variant] ?: true
            sameVariant.filter { kotlin.math.abs(it.order - t.line.order) == 1 }.forEach { l ->
                val below = l.order > t.line.order
                roleOf(l.text)?.let { role -> out[role] = maxOf(out[role] ?: 0f, if (below == labelsBelow) 0.5f else 0.3f) }
            }
        }
        return out
    }

    data class Triple3(val q: Scored, val p: Scored, val t: Scored, val score: Float, val margin: Float = 1f)

    private fun bestTriple(all: List<Scored>): Triple3? {
        val cands = all.filter { it.token.value < BigDecimal(1_000_000) }.take(40)
        var best: Triple3? = null
        var runnerUp: Triple3? = null
        for (t in cands) for (q in cands) for (p in cands) {
            if (q === p || q === t || p === t) continue
            if (q.token.value > t.token.value && p.token.value > t.token.value) continue
            if (!Validator.consistent(q.token.value, p.token.value, t.token.value)) continue
            val s = score(q, p, t)
            if (best == null || s > best.score) { runnerUp = best; best = Triple3(q, p, t, s) }
            else if (runnerUp == null || s > runnerUp.score) runnerUp = Triple3(q, p, t, s)
        }
        // Record how decisive the choice was (used for confidence).
        return best?.copy(margin = if (runnerUp != null) best.score - runnerUp.score else 1f)
    }

    private fun score(q: Scored, p: Scored, t: Scored): Float {
        var s = 1f
        s += roleScore(q, Role.QUANTITY) + roleScore(p, Role.PRICE) + roleScore(t, Role.TOTAL)
        listOf(q, p, t).forEach {
            if (it.token.synthetic) s -= 0.6f
            if (it.seenInVariants > 1) s += 0.1f
            if (it.token.decimals > 3) s -= 0.3f
        }
        // Weak priors that hold on most dispensers: the amount is shown first, volume before price.
        val ot = t.token.line.order; val oq = q.token.line.order; val op = p.token.line.order
        if (ot <= oq && ot <= op) s += 0.15f
        if (oq <= op) s += 0.1f
        // Unit prices are rarely given to more than 2–3 decimals and volumes rarely exceed 300 L.
        if (q.token.value > BigDecimal(400)) s -= 0.4f
        return s
    }

    private fun roleScore(x: Scored, want: Role): Float {
        val h = x.hints
        var s = h[want] ?: 0f
        for ((role, w) in h) if (role != want) s -= w * if (role == Role.OTHER) 1.5f else 0.7f
        return s
    }

    private fun fromTriple(tr: Triple3, all: List<Scored>): Map<OcrField, FieldResult> {
        // Consistent arithmetic is strong evidence. Labels and a clear winner push it higher.
        val base = 0.72f + (if (tr.margin > 0.3f) 0.1f else 0f)
        fun conf(x: Scored, role: Role): Float {
            var c = base + 0.12f * ((x.hints[role] ?: 0f).coerceAtMost(1f))
            if (x.token.synthetic) c -= 0.15f
            if (x.seenInVariants > 1) c += 0.04f
            return c.coerceIn(0.05f, 0.98f)
        }
        val reason = "Fuel × price matches total"
        fun result(field: OcrField, x: Scored, role: Role) = FieldResult(
            field,
            Candidate(x.token.value, x.token.raw, conf(x, role), reason),
            alternatives(all, x, role),
        )
        return mapOf(
            OcrField.FUEL_QUANTITY to result(OcrField.FUEL_QUANTITY, tr.q, Role.QUANTITY),
            OcrField.PRICE_PER_UNIT to result(OcrField.PRICE_PER_UNIT, tr.p, Role.PRICE),
            OcrField.TOTAL_AMOUNT to result(OcrField.TOTAL_AMOUNT, tr.t, Role.TOTAL),
        )
    }

    private fun fromLabels(all: List<Scored>): Map<OcrField, FieldResult> {
        val used = HashSet<Scored>()
        fun pick(field: OcrField, role: Role): FieldResult {
            val ranked = all.filter { !it.token.synthetic && it !in used }
                .map { it to roleScore(it, role) }
                .filter { it.second > 0f }
                .sortedByDescending { it.second }
            val top = ranked.firstOrNull()?.first
            if (top != null) used += top
            val best = top?.let { Candidate(it.token.value, it.token.raw, (0.35f + 0.3f * (it.hints[role] ?: 0f)).coerceAtMost(0.7f), "Matched label only") }
            return FieldResult(field, best, alternatives(all, top, role))
        }
        val total = pick(OcrField.TOTAL_AMOUNT, Role.TOTAL)
        val qty = pick(OcrField.FUEL_QUANTITY, Role.QUANTITY)
        val price = pick(OcrField.PRICE_PER_UNIT, Role.PRICE)
        return listOf(total, qty, price).associateBy { it.field }
    }

    private fun alternatives(all: List<Scored>, chosen: Scored?, role: Role): List<Candidate> =
        all.asSequence()
            .filter { it !== chosen && !it.token.synthetic && (it.hints[Role.OTHER] ?: 0f) < 0.5f }
            .sortedByDescending { roleScore(it, role) }
            .take(4)
            .map { Candidate(it.token.value, it.token.raw, 0.3f, "Other number in photo") }
            .toList()

    /** quantity × price, used to fill a missing total (shown to the user as calculated). */
    fun computeTotal(quantity: BigDecimal, price: BigDecimal): BigDecimal = quantity.multiply(price, mc)
}
