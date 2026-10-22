package app.tel.domain.validation

import app.tel.domain.format.CurrencyInfo
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.Vehicle
import java.math.BigDecimal
import java.math.MathContext

enum class Severity { ERROR, WARNING }

/** [field] matches the review-form field keys so the UI can show the message next to the input. */
data class Issue(val field: String, val severity: Severity, val message: String)

object Fields {
    const val ODO_BEFORE = "odometer_before"
    const val ODO_AFTER = "odometer_after"
    const val LEVEL_BEFORE = "fuel_level_before"
    const val LEVEL_AFTER = "fuel_level_after"
    const val FUEL_ADDED = "fuel_added"
    const val PRICE = "price_per_unit"
    const val TOTAL = "total_cost"
    const val CURRENCY = "currency"
    const val TIMESTAMP = "timestamp"
}

/**
 * Validates a refuelling before it is saved. Errors block saving; warnings are shown and the user
 * decides. Values are never modified here.
 */
object Validator {
    private val hundred = BigDecimal(100)
    private val mc = MathContext.DECIMAL64

    fun validate(
        ev: RefuelEvent,
        vehicle: Vehicle?,
        previous: RefuelEvent?,
        next: RefuelEvent? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<Issue> {
        val out = mutableListOf<Issue>()
        fun err(f: String, m: String) = out.add(Issue(f, Severity.ERROR, m))
        fun warn(f: String, m: String) = out.add(Issue(f, Severity.WARNING, m))

        ev.odometerBeforeKm?.let { if (it.signum() < 0) err(Fields.ODO_BEFORE, "Odometer can't be negative.") }
        ev.odometerAfterKm?.let { if (it.signum() < 0) err(Fields.ODO_AFTER, "Odometer can't be negative.") }
        ev.fuelLevelBeforePct?.let { if (it.signum() < 0 || it > hundred) err(Fields.LEVEL_BEFORE, "Fuel level must be between 0 and 100 %.") }
        ev.fuelLevelAfterPct?.let { if (it.signum() < 0 || it > hundred) err(Fields.LEVEL_AFTER, "Fuel level must be between 0 and 100 %.") }
        ev.fuelAddedL?.let { if (it.signum() < 0) err(Fields.FUEL_ADDED, "Fuel added can't be negative.") }
        ev.pricePerL?.let { if (it.signum() < 0) err(Fields.PRICE, "Price can't be negative.") }
        ev.totalCost?.let { if (it.signum() < 0) err(Fields.TOTAL, "Total can't be negative.") }
        if (!CurrencyInfo.isValid(ev.currencyCode)) err(Fields.CURRENCY, "\"${ev.currencyCode}\" is not a valid currency code.")

        if (out.any { it.severity == Severity.ERROR }) return out

        val before = ev.odometerBeforeKm
        val after = ev.odometerAfterKm
        if (before != null && after != null) {
            if (after < before) warn(Fields.ODO_AFTER, "Odometer after refuelling is lower than before.")
            else if (after.subtract(before) > BigDecimal(50)) warn(Fields.ODO_AFTER, "Odometer changed by more than 50 km during refuelling.")
        }

        val prevReading = previous?.readingAfterKm
        val reading = ev.readingAtPumpKm
        if (prevReading != null && reading != null) {
            if (reading < prevReading) warn(Fields.ODO_BEFORE, "Odometer is lower than the previous refuelling (${prevReading.toPlainString()} km).")
            else if (reading.subtract(prevReading) > BigDecimal(3000)) warn(Fields.ODO_BEFORE, "More than 3,000 km since the previous refuelling. Was a refuelling missed?")
        }
        val nextReading = next?.readingAtPumpKm
        if (nextReading != null && ev.readingAfterKm != null && ev.readingAfterKm!! > nextReading) {
            warn(Fields.ODO_AFTER, "Odometer is higher than the following refuelling (${nextReading.toPlainString()} km).")
        }

        val lb = ev.fuelLevelBeforePct
        val la = ev.fuelLevelAfterPct
        if (lb != null && la != null && la < lb) warn(Fields.LEVEL_AFTER, "Fuel level after is lower than before.")

        val cap = vehicle?.tankCapacityL
        val added = ev.fuelAddedL
        if (cap != null && added != null && added > cap.multiply(BigDecimal("1.05"))) {
            warn(Fields.FUEL_ADDED, "Fuel added is more than the tank capacity (${cap.toPlainString()} L).")
        }
        if (added != null && added.signum() == 0) warn(Fields.FUEL_ADDED, "Fuel added is zero.")

        val price = ev.pricePerL
        val total = ev.totalCost
        if (added != null && price != null && total != null && !consistent(added, price, total)) {
            val expected = added.multiply(price, mc).setScale(2, java.math.RoundingMode.HALF_UP)
            warn(Fields.TOTAL, "Fuel × price = ${expected.toPlainString()}, which doesn't match the total.")
        }

        if (ev.timestamp > nowMillis + 10 * 60_000) warn(Fields.TIMESTAMP, "Date is in the future.")
        return out
    }

    /**
     * quantity × price ≈ total, allowing for each displayed value being rounded to its shown decimals.
     * Pumps compute the amount from the unrounded volume, so error scales with price and quantity.
     */
    fun consistent(quantity: BigDecimal, price: BigDecimal, total: BigDecimal): Boolean {
        val product = quantity.multiply(price, mc)
        val qHalfStep = halfStep(quantity)
        val pHalfStep = halfStep(price)
        val tol = price.abs().multiply(qHalfStep).add(quantity.abs().multiply(pHalfStep)).add(halfStep(total)).add(BigDecimal("0.011"))
        return product.subtract(total).abs() <= tol
    }

    private fun halfStep(v: BigDecimal): BigDecimal {
        val scale = v.stripTrailingZeros().scale().coerceAtLeast(0).coerceAtLeast(v.scale().coerceAtLeast(0))
        return BigDecimal.ONE.movePointLeft(scale).divide(BigDecimal(2))
    }
}
