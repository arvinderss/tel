package app.tel.domain.validation

import app.tel.domain.format.CurrencyInfo
import app.tel.domain.model.RefuelEvent
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

enum class Severity { ERROR, WARNING }

/** [field] matches the form field keys so the UI can show the message next to the input. */
data class Issue(val field: String, val severity: Severity, val message: String)

object Fields {
    const val ODOMETER = "odometer"
    const val FUEL_ADDED = "fuel_added"
    const val PRICE = "price_per_unit"
    const val TOTAL = "total_cost"
    const val CURRENCY = "currency"
    const val TIMESTAMP = "timestamp"
    const val FORM = "form"
}

/**
 * Validates a fill-up before saving. Errors block saving; warnings are shown and the user
 * decides. Values are never modified here.
 */
object Validator {
    private val mc = MathContext.DECIMAL64

    /**
     * @param previous the fill-up dated just before this one (same vehicle) that has an odometer reading.
     * @param next the fill-up dated just after this one that has an odometer reading.
     */
    fun validate(
        ev: RefuelEvent,
        previous: RefuelEvent?,
        next: RefuelEvent? = null,
        nowMillis: Long = System.currentTimeMillis(),
        formatKm: (BigDecimal) -> String = { it.stripTrailingZeros().toPlainString() + " km" },
    ): List<Issue> {
        val out = mutableListOf<Issue>()
        fun err(f: String, m: String) = out.add(Issue(f, Severity.ERROR, m))
        fun warn(f: String, m: String) = out.add(Issue(f, Severity.WARNING, m))

        if (ev.isEmpty) err(Fields.FORM, "Enter at least one value.")
        ev.odometerKm?.let { if (it.signum() < 0) err(Fields.ODOMETER, "Odometer can't be negative.") }
        ev.fuelAddedL?.let { if (it.signum() <= 0) err(Fields.FUEL_ADDED, "Volume must be more than zero.") }
        ev.pricePerL?.let { if (it.signum() <= 0) err(Fields.PRICE, "Rate must be more than zero.") }
        ev.totalCost?.let { if (it.signum() < 0) err(Fields.TOTAL, "Amount can't be negative.") }
        if (!CurrencyInfo.isValid(ev.currencyCode)) err(Fields.CURRENCY, "\"${ev.currencyCode}\" is not a valid currency code.")

        val odo = ev.odometerKm
        val prevOdo = previous?.odometerKm
        val nextOdo = next?.odometerKm
        if (odo != null && odo.signum() >= 0) {
            if (prevOdo != null && odo < prevOdo) {
                err(Fields.ODOMETER, "Lower than the earlier fill-up (${formatKm(prevOdo)}). Check the reading or the date.")
            } else if (nextOdo != null && odo > nextOdo) {
                err(Fields.ODOMETER, "Higher than the later fill-up (${formatKm(nextOdo)}). Check the reading or the date.")
            } else if (prevOdo != null && odo.subtract(prevOdo) > BigDecimal(3000) && !ev.missedPrevious) {
                warn(Fields.ODOMETER, "More than 3,000 km since the last fill-up. If you missed logging one, turn on \"Missed a fill-up\".")
            }
        }

        val q = ev.fuelAddedL; val p = ev.pricePerL; val t = ev.totalCost
        if (q != null && p != null && t != null && q.signum() > 0 && p.signum() > 0 && !consistent(q, p, t)) {
            val expected = q.multiply(p, mc).setScale(2, RoundingMode.HALF_UP)
            warn(Fields.TOTAL, "Rate × volume = ${expected.toPlainString()}, which doesn't match the amount.")
        }

        if (ev.timestamp > nowMillis + 10 * 60_000) warn(Fields.TIMESTAMP, "Date is in the future.")
        return out
    }

    /**
     * volume × rate ≈ amount, allowing for each displayed value being rounded to its shown decimals.
     * Pumps compute the amount from the unrounded volume, so the error scales with rate and volume.
     */
    fun consistent(quantity: BigDecimal, price: BigDecimal, total: BigDecimal): Boolean {
        val product = quantity.multiply(price, mc)
        val tol = price.abs().multiply(halfStep(quantity)).add(quantity.abs().multiply(halfStep(price))).add(halfStep(total)).add(BigDecimal("0.011"))
        return product.subtract(total).abs() <= tol
    }

    private fun halfStep(v: BigDecimal): BigDecimal {
        val scale = maxOf(v.stripTrailingZeros().scale(), v.scale(), 0)
        return BigDecimal.ONE.movePointLeft(scale).divide(BigDecimal(2))
    }
}
