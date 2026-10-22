package app.tel.domain.format

import java.math.BigDecimal
import java.math.RoundingMode

/** Parses what the user typed: accepts "1234.5", "1,234.5", "1234,5". Returns null when blank or invalid. */
object Input {
    fun parse(text: String): BigDecimal? {
        val t = text.trim().replace(" ", "")
        if (t.isEmpty()) return null
        if (t.any { !it.isDigit() && it != '.' && it != ',' }) return null
        val normalized = when {
            t.contains('.') && t.contains(',') -> if (t.lastIndexOf('.') > t.lastIndexOf(',')) t.replace(",", "") else t.replace(".", "").replace(',', '.')
            t.count { it == ',' } == 1 && t.length - t.indexOf(',') - 1 != 3 -> t.replace(',', '.')
            else -> t.replace(",", "")
        }
        if (normalized.count { it == '.' } > 1) return null
        return runCatching { BigDecimal(normalized.trimEnd('.')) }.getOrNull()
    }

    fun isInvalid(text: String): Boolean = text.isNotBlank() && parse(text) == null

    /** Formats a stored value for an input box (no grouping, trimmed zeros, at most [scale] decimals). */
    fun format(v: BigDecimal?, scale: Int = 3): String =
        v?.setScale(scale, RoundingMode.HALF_UP)?.stripTrailingZeros()?.let {
            if (it.scale() < 0) it.setScale(0) else it
        }?.toPlainString() ?: ""
}

