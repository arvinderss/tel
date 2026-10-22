package app.tel.domain.model

import java.math.BigDecimal

/**
 * Domain models. All measurements are stored in canonical units:
 * distance in kilometres, fuel in litres, prices per litre, money as exact decimals.
 * Conversion to the user's display units happens only at the edges (see [app.tel.domain.units]).
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
    /** Tank capacity in litres; null when unknown. */
    val tankCapacityL: BigDecimal? = null,
    /** ISO 4217 code, e.g. "INR". */
    val currencyCode: String = "INR",
    val registration: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** One refuelling event as entered/confirmed by the user. Everything optional except identity. */
data class RefuelEvent(
    val id: String,
    val vehicleId: String,
    /** Epoch milliseconds (UTC instant). Displayed in the device time zone. */
    val timestamp: Long,
    val odometerBeforeKm: BigDecimal? = null,
    val odometerAfterKm: BigDecimal? = null,
    /** Fuel gauge reading in percent (0–100), stored exactly as read. */
    val fuelLevelBeforePct: BigDecimal? = null,
    val fuelLevelAfterPct: BigDecimal? = null,
    val fuelAddedL: BigDecimal? = null,
    val pricePerL: BigDecimal? = null,
    val totalCost: BigDecimal? = null,
    val currencyCode: String = "INR",
    /** The user filled the tank to full (pump auto shut-off). Enables the measured full-to-full method. */
    val filledToFull: Boolean = false,
    val notes: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** The odometer reading at the pump (before the fill, falling back to the after reading). */
    val readingAtPumpKm: BigDecimal? get() = odometerBeforeKm ?: odometerAfterKm

    /** The odometer reading when the car left the pump. */
    val readingAfterKm: BigDecimal? get() = odometerAfterKm ?: odometerBeforeKm
}

enum class CalculationQuality(val label: String, val description: String) {
    MEASURED("Measured", "Based on pump-measured fuel with the tank refilled to the same level."),
    ESTIMATED("Estimated", "Uses fuel-gauge readings and tank capacity; gauges are approximate."),
    PARTIAL("Partial", "Assumes the tank was refilled to the same level as last time."),
    INSUFFICIENT("Insufficient data", "Not enough information to calculate mileage."),
}

enum class ConsumptionMethod(val label: String) {
    FULL_TO_FULL("Full tank to full tank"),
    SAME_LEVEL("Refilled to the same gauge level"),
    ADDED_ADJUSTED_BY_GAUGE("Fuel added, adjusted for gauge change"),
    GAUGE_ONLY("Gauge drop × tank capacity"),
    ADDED_ASSUMED_SAME_LEVEL("Fuel added (same level assumed)"),
    NONE("Not calculated"),
}

/** Derived values for one event, always recomputed from the stored raw values. */
data class RefuelMetrics(
    val eventId: String,
    val distanceKm: BigDecimal? = null,
    val fuelConsumedL: BigDecimal? = null,
    val kmPerL: BigDecimal? = null,
    val costPerKm: BigDecimal? = null,
    val quality: CalculationQuality = CalculationQuality.INSUFFICIENT,
    val method: ConsumptionMethod = ConsumptionMethod.NONE,
    /** Short human explanation when something could not be calculated. */
    val note: String? = null,
    val isFirst: Boolean = false,
)

enum class ImageType(val key: String, val label: String, val fileName: String) {
    BEFORE_HUD("before_hud", "Before refuelling", "before.jpg"),
    AFTER_HUD("after_hud", "After refuelling", "after.jpg"),
    PUMP_METER("pump_meter", "Pump meter", "pump.jpg");

    companion object {
        fun fromKey(key: String): ImageType? = entries.firstOrNull { it.key == key }
    }
}

/** Where a value on the review screen came from. */
enum class ValueSource(val label: String) { OCR("Read from photo"), USER("Edited"), CALCULATED("Calculated"), EMPTY("") }
