package app.tel.domain.model

import java.math.BigDecimal

/**
 * Domain models. Measurements are stored in canonical units: distance in kilometres,
 * fuel in litres, price per litre, money as exact decimals. Conversion to the user's
 * display units happens only at the edges (see [app.tel.domain.units]).
 */

enum class UnitSystem { METRIC, US }

enum class FuelType(val label: String) {
    PETROL("Petrol / Gasoline"),
    DIESEL("Diesel"),
    CNG("CNG"),
    LPG("LPG"),
    OTHER("Other");
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Vehicle(
    val id: String,
    val name: String,
    val make: String = "",
    val model: String = "",
    val year: Int? = null,
    val fuelType: FuelType = FuelType.PETROL,
    /** Kept for older records and backups; no longer used in calculations. */
    val tankCapacityL: BigDecimal? = null,
    /** ISO 4217 code, e.g. "INR". */
    val currencyCode: String = "INR",
    val registration: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** One fill-up. Everything except identity and date is optional. */
data class RefuelEvent(
    val id: String,
    val vehicleId: String,
    /** Epoch milliseconds (UTC instant). Displayed in the device time zone. */
    val timestamp: Long,
    /** Odometer reading at the pump. */
    val odometerKm: BigDecimal? = null,
    val fuelAddedL: BigDecimal? = null,
    val pricePerL: BigDecimal? = null,
    val totalCost: BigDecimal? = null,
    val currencyCode: String = "INR",
    /** The tank was filled until the pump clicked off. */
    val filledToFull: Boolean = true,
    /** A fill-up before this one was never logged, so mileage across this gap is unknown. */
    val missedPrevious: Boolean = false,
    val notes: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = odometerKm == null && fuelAddedL == null && pricePerL == null && totalCost == null
}

/** Why a fill-up has (or hasn't) a mileage figure. */
enum class MileageStatus(val label: String, val description: String) {
    MEASURED("Measured", "Distance between two full tanks ÷ all fuel added in between."),
    FIRST("Starting point", "Mileage is calculated from the next full tank."),
    PARTIAL("Partial fill", "Mileage will be calculated at the next full tank, including this fuel."),
    AFTER_GAP("After missed fill-up", "A fill-up wasn't logged before this one, so this is a new starting point."),
    MISSING_DATA("Not enough data", "An odometer reading or fuel volume is missing in this stretch."),
}

/** Derived values for one fill-up, always recomputed from the stored raw values. */
data class RefuelMetrics(
    val eventId: String,
    /** Odometer difference from the previous logged fill-up. */
    val distanceSincePreviousKm: BigDecimal? = null,
    val status: MileageStatus = MileageStatus.MISSING_DATA,
    /** Only for MEASURED: the full-tank-to-full-tank stretch ending at this fill-up. */
    val spanDistanceKm: BigDecimal? = null,
    val spanFuelL: BigDecimal? = null,
    val spanCost: BigDecimal? = null,
    val kmPerL: BigDecimal? = null,
    val costPerKm: BigDecimal? = null,
    /** Number of fill-ups (including this one) whose fuel went into the stretch. */
    val spanFills: Int = 0,
    val note: String? = null,
)
