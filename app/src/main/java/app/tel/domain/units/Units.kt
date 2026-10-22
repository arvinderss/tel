package app.tel.domain.units

import app.tel.domain.model.UnitSystem
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exact unit conversions between the canonical storage units (km, litres) and display units.
 * US units use the US gallon (3.785411784 L), never the Imperial gallon.
 */
object Units {
    val LITRES_PER_US_GALLON = BigDecimal("3.785411784")
    val KM_PER_MILE = BigDecimal("1.609344")
    private val MC = MathContext.DECIMAL128
    private val HUNDRED = BigDecimal(100)

    // ---- distance ----
    fun kmToDisplay(km: BigDecimal, system: UnitSystem): BigDecimal =
        if (system == UnitSystem.US) km.divide(KM_PER_MILE, MC) else km

    fun displayToKm(value: BigDecimal, system: UnitSystem): BigDecimal =
        if (system == UnitSystem.US) value.multiply(KM_PER_MILE, MC) else value

    // ---- volume ----
    fun litresToDisplay(l: BigDecimal, system: UnitSystem): BigDecimal =
        if (system == UnitSystem.US) l.divide(LITRES_PER_US_GALLON, MC) else l

    fun displayToLitres(value: BigDecimal, system: UnitSystem): BigDecimal =
        if (system == UnitSystem.US) value.multiply(LITRES_PER_US_GALLON, MC) else value

    // ---- price per volume (money / litre <-> money / gallon) ----
    fun pricePerLToDisplay(pricePerL: BigDecimal, system: UnitSystem): BigDecimal =
        if (system == UnitSystem.US) pricePerL.multiply(LITRES_PER_US_GALLON, MC) else pricePerL

    fun displayPriceToPerL(value: BigDecimal, system: UnitSystem): BigDecimal =
        if (system == UnitSystem.US) value.divide(LITRES_PER_US_GALLON, MC) else value

    // ---- economy ----
    /** km/L -> km/L (metric) or miles per US gallon. */
    fun economyToDisplay(kmPerL: BigDecimal, system: UnitSystem): BigDecimal =
        if (system == UnitSystem.US) kmPerL.multiply(LITRES_PER_US_GALLON, MC).divide(KM_PER_MILE, MC) else kmPerL

    /** km/L -> L/100 km. Null when the economy is zero. */
    fun litresPer100Km(kmPerL: BigDecimal): BigDecimal? =
        if (kmPerL.signum() <= 0) null else HUNDRED.divide(kmPerL, MC)

    // ---- cost per distance ----
    fun costPerKmToDisplay(costPerKm: BigDecimal, system: UnitSystem): BigDecimal =
        if (system == UnitSystem.US) costPerKm.multiply(KM_PER_MILE, MC) else costPerKm

    fun distanceUnit(system: UnitSystem) = if (system == UnitSystem.US) "mi" else "km"
    fun volumeUnit(system: UnitSystem) = if (system == UnitSystem.US) "gal" else "L"
    fun economyUnit(system: UnitSystem) = if (system == UnitSystem.US) "MPG" else "km/L"

    fun round(value: BigDecimal, scale: Int): BigDecimal = value.setScale(scale, RoundingMode.HALF_UP)
}
