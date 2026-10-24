package app.tel.domain.calc

import app.tel.domain.format.Input
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

enum class PumpField { AMOUNT, RATE, VOLUME }

enum class EntrySource { EMPTY, TYPED, PREFILLED, CALCULATED }

data class PumpValue(val text: String = "", val source: EntrySource = EntrySource.EMPTY) {
    val value: BigDecimal? get() = Input.parse(text)
}

/**
 * Amount = rate × volume. Once two of the three are given, the third is calculated.
 *
 * "Given" means typed by the user or prefilled (e.g. last fill-up's rate). When all three end up
 * given, the one given *least recently* is recalculated from the other two, so the newest
 * entries always win. Prefilled values count as the oldest.
 *
 * Immutable: every change returns a new state. Pure Kotlin, unit-tested.
 */
data class PumpTriad(
    val values: Map<PumpField, PumpValue> = PumpField.entries.associateWith { PumpValue() },
    /** Given fields, oldest first. */
    val order: List<PumpField> = emptyList(),
    val amountScale: Int = 2,
    val rateScale: Int = 2,
    val volumeScale: Int = 2,
) {
    operator fun get(f: PumpField): PumpValue = values.getValue(f)

    fun edit(field: PumpField, text: String): PumpTriad {
        val v = values.toMutableMap()
        val ord = order.toMutableList()
        ord.remove(field)
        if (text.isBlank()) {
            v[field] = PumpValue()
        } else {
            v[field] = PumpValue(text, EntrySource.TYPED)
            ord.add(field)
        }
        if (ord.size == 3) ord.removeAt(0) // oldest given value becomes the calculated one
        return copy(values = v, order = ord).recalculate()
    }

    /** Sets a value the user didn't type (e.g. last rate). Never overrides a given value. */
    fun prefill(field: PumpField, value: BigDecimal): PumpTriad {
        if (field in order) return this
        val v = values.toMutableMap()
        v[field] = PumpValue(Input.format(value, scaleOf(field)), EntrySource.PREFILLED)
        return copy(values = v, order = listOf(field) + order).let { if (it.order.size == 3) it.copy(order = it.order.drop(1)) else it }.recalculate()
    }

    private fun recalculate(): PumpTriad {
        val v = values.toMutableMap()
        // Drop stale calculated values.
        for (f in PumpField.entries) if (v.getValue(f).source == EntrySource.CALCULATED) v[f] = PumpValue()
        if (order.size == 2) {
            val target = PumpField.entries.first { it !in order }
            val result = calculate(target, v)
            v[target] = if (result != null) PumpValue(Input.format(result, scaleOf(target)), EntrySource.CALCULATED) else PumpValue()
        }
        return copy(values = v)
    }

    private fun calculate(target: PumpField, v: Map<PumpField, PumpValue>): BigDecimal? {
        val a = v.getValue(PumpField.AMOUNT).value
        val r = v.getValue(PumpField.RATE).value
        val q = v.getValue(PumpField.VOLUME).value
        val mc = MathContext.DECIMAL64
        return when (target) {
            PumpField.AMOUNT -> if (r != null && q != null) r.multiply(q, mc).setScale(amountScale, RoundingMode.HALF_UP) else null
            PumpField.RATE -> if (a != null && q != null && q.signum() > 0) a.divide(q, rateScale, RoundingMode.HALF_UP) else null
            PumpField.VOLUME -> if (a != null && r != null && r.signum() > 0) a.divide(r, volumeScale, RoundingMode.HALF_UP) else null
        }
    }

    private fun scaleOf(f: PumpField) = when (f) {
        PumpField.AMOUNT -> amountScale
        PumpField.RATE -> rateScale
        PumpField.VOLUME -> volumeScale
    }

    companion object {
        /** State for editing an existing record: everything stored counts as given. */
        fun fromStored(amount: BigDecimal?, rate: BigDecimal?, volume: BigDecimal?, amountScale: Int = 2, rateScale: Int = 2, volumeScale: Int = 2): PumpTriad {
            val base = PumpTriad(amountScale = amountScale, rateScale = rateScale, volumeScale = volumeScale)
            val stored = listOf(PumpField.RATE to rate, PumpField.VOLUME to volume, PumpField.AMOUNT to amount)
            val v = base.values.toMutableMap()
            val ord = mutableListOf<PumpField>()
            for ((f, x) in stored) if (x != null) {
                v[f] = PumpValue(Input.format(x, base.scaleOf(f)), EntrySource.TYPED); ord += f
            }
            return base.copy(values = v, order = ord)
        }
    }
}
