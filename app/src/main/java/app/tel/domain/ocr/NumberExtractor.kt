package app.tel.domain.ocr

import java.math.BigDecimal

/** A number found in OCR text, with where it came from. */
data class NumToken(
    val value: BigDecimal,
    val raw: String,
    val line: OcrLine,
    /** True if the number was produced by guessing a missing decimal point. */
    val synthetic: Boolean = false,
    /** Text immediately following the number on the same line (lower-case, trimmed), e.g. "km", "%". */
    val suffix: String = "",
    val prefix: String = "",
) {
    val decimals: Int get() = value.scale().coerceAtLeast(0)
}

/**
 * Pulls numbers out of noisy OCR text.
 *
 * Handles common seven-segment / LCD confusions (O→0, D→0, I/l/|→1, S→5, B→8, Z→2, G→6),
 * thousands separators, decimal commas, and digit groups split by a gap ("143 140").
 */
object NumberExtractor {
    private val confusable = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'U' to '0',
        'I' to '1', 'l' to '1', '|' to '1', 'i' to '1', '!' to '1',
        'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2', 'z' to '2', 'G' to '6', 'b' to '6', 'q' to '9', 'g' to '9',
    )

    // A run of digit-like characters, optionally separated by single spaces, dots or commas.
    private val runRegex = Regex("""[0-9OoDQUIl|i!SsBZzGbqg](?:[0-9OoDQUIl|i!SsBZzGbqg]|[.,·](?=[0-9OoDQUIl|i!SsBZzGbqg])| (?=[0-9OoDQUIl|i!SsBZzGbqg]))*""")

    fun extract(line: OcrLine, allowSpaceJoin: Boolean = true): List<NumToken> {
        val text = line.text
        val out = mutableListOf<NumToken>()
        for (m in runRegex.findAll(text)) {
            val raw = m.value.trim()
            val realDigits = raw.count { it.isDigit() }
            if (realDigits < 1) continue
            val digitLike = raw.count { it.isDigit() || it in confusable }
            // Reject words like "Bio" or "Sold": most characters must be real digits.
            if (realDigits * 10 < digitLike * 6 && realDigits < 3) continue
            // Don't let a word glued to the number (e.g. "lit") turn into digits: trim leading/trailing letters.
            val trimmed = raw.trim { !it.isDigit() && it != '.' && it != ',' }
            if (trimmed.isEmpty()) continue
            for (piece in groupPieces(trimmed, allowSpaceJoin)) {
                val normalized = normalize(piece) ?: continue
                out += NumToken(
                    value = normalized,
                    raw = piece,
                    line = line,
                    suffix = text.substring(m.range.last + 1).trim().lowercase().take(8),
                    prefix = text.substring(0, m.range.first).trim().lowercase().takeLast(8),
                )
                // A joined value ("143 140") might really be two numbers: offer the groups as alternatives.
                if (piece.contains(' ')) {
                    piece.split(' ').filter { it.isNotBlank() }.forEach { p ->
                        normalize(p)?.let { v -> out += NumToken(v, p, line, synthetic = true) }
                    }
                }
            }
        }
        return out
    }

    /**
     * Splits a run on spaces, then re-joins groups that look like one number: thousands groups
     * ("52 431", "143 140") or LCD segment gaps that leave a single stray digit ("1 12.41").
     * Groups after one that already has a decimal point start a new number ("756.8 110.68").
     */
    internal fun groupPieces(run: String, allowJoin: Boolean): List<String> {
        val groups = run.split(' ').filter { it.isNotEmpty() }
        if (!allowJoin || groups.size < 2) return groups
        val out = mutableListOf<String>()
        var cur = groups[0]
        for (g in groups.drop(1)) {
            val curHasSep = cur.any { it == '.' || it == ',' }
            val lastGroup = cur.substringAfterLast(' ')
            val looksJoined = !curHasSep && (
                g.takeWhile { it != '.' && it != ',' }.length == 3 || lastGroup.length == 1 || g.length == 1
                )
            if (looksJoined) cur = "$cur $g" else { out += cur; cur = g }
        }
        out += cur
        return out
    }

    /** Converts one candidate string to a number, or null if it isn't one. */
    fun normalize(s: String): BigDecimal? {
        var t = s.map { c -> if (c.isDigit() || c == '.' || c == ',' || c == ' ' || c == '·') c else confusable[c] ?: return null }
            .joinToString("")
            .replace('·', '.')
        // Digit groups split by spaces (LCD gaps, "143 140") – join them.
        t = t.replace(" ", "")
        if (t.isEmpty() || !t.first().isDigit() && t.first() != '.') return null
        val dots = t.count { it == '.' }
        val commas = t.count { it == ',' }
        t = when {
            dots > 0 && commas > 0 -> {
                // The later separator is the decimal one.
                if (t.lastIndexOf('.') > t.lastIndexOf(',')) t.replace(",", "") else t.replace(".", "").replace(',', '.')
            }
            commas > 0 -> {
                val afterLast = t.length - t.lastIndexOf(',') - 1
                if (commas == 1 && afterLast in 1..2) t.replace(',', '.') else t.replace(",", "")
            }
            dots > 1 -> {
                val afterLast = t.length - t.lastIndexOf('.') - 1
                if (afterLast == 3) t.replace(".", "") else t.substring(0, t.lastIndexOf('.')).replace(".", "") + t.substring(t.lastIndexOf('.'))
            }
            else -> t
        }
        if (t.endsWith('.')) t = t.dropLast(1)
        return runCatching { BigDecimal(t) }.getOrNull()
    }

    /**
     * When a display's decimal point is missed ("408610" for 4086.10), propose versions with the
     * decimal point inserted [places] digits from the right.
     */
    fun withInsertedDecimal(token: NumToken, places: IntArray = intArrayOf(2, 1, 3)): List<NumToken> {
        if (token.value.scale() > 0) return emptyList()
        val digits = token.value.toPlainString()
        return places.filter { digits.length > it }.map { p ->
            NumToken(token.value.movePointLeft(p), token.raw, token.line, synthetic = true, suffix = token.suffix, prefix = token.prefix)
        }
    }
}
