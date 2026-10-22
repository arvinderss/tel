package app.tel.domain.calc

import app.tel.domain.model.CalculationQuality
import app.tel.domain.model.ConsumptionMethod
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import java.math.BigDecimal
import java.math.MathContext

/**
 * Deterministic mileage / consumption engine. Pure Kotlin: no Android, no I/O.
 *
 * For each event (in chronological order, per vehicle) it computes the distance driven since the
 * previous fill and the fuel burnt over that distance, then km/L and cost per km.
 *
 * Fuel consumed between fill N-1 and fill N is the fuel that would be needed to bring the tank back
 * to the level it had when the car left fill N-1. Methods, best first:
 *
 *  1. FULL_TO_FULL   – both fills were to full: consumed = fuel added now. (Measured)
 *  2. SAME_LEVEL     – gauge after both fills reads the same (±[sameLevelTolerancePct]):
 *                      consumed = fuel added now. (Measured)
 *  3. ADDED_ADJUSTED_BY_GAUGE – after-fill gauge differs and tank capacity is known:
 *                      consumed = added − (levelAfterNow − levelAfterPrev) × capacity. (Estimated)
 *  4. GAUGE_ONLY     – no fuel-added figure, but gauges + capacity known:
 *                      consumed = (levelAfterPrev − levelBeforeNow) × capacity. (Estimated)
 *  5. ADDED_ASSUMED_SAME_LEVEL – only fuel added is known: consumed = added. (Partial)
 */
class CalculationEngine(
    private val sameLevelTolerancePct: BigDecimal = BigDecimal("1.5"),
) {
    private val mc = MathContext.DECIMAL64
    private val hundred = BigDecimal(100)

    /** Sorts events chronologically; ties broken by odometer then creation time. */
    fun chronological(events: List<RefuelEvent>): List<RefuelEvent> =
        events.sortedWith(
            compareBy<RefuelEvent> { it.timestamp }
                .thenBy { it.readingAtPumpKm ?: BigDecimal.ZERO }
                .thenBy { it.createdAt },
        )

    /** Computes metrics for all events of ONE vehicle. */
    fun compute(events: List<RefuelEvent>, tankCapacityL: BigDecimal?): Map<String, RefuelMetrics> {
        val ordered = chronological(events)
        val result = LinkedHashMap<String, RefuelMetrics>()
        ordered.forEachIndexed { i, ev ->
            result[ev.id] = if (i == 0) {
                RefuelMetrics(ev.id, isFirst = true, note = "Available after the next refuelling.")
            } else {
                computeOne(ordered[i - 1], ev, tankCapacityL)
            }
        }
        return result
    }

    fun computeOne(prev: RefuelEvent, cur: RefuelEvent, tankCapacityL: BigDecimal?): RefuelMetrics {
        val prevReading = prev.readingAfterKm
        val curReading = cur.readingAtPumpKm
        if (prevReading == null || curReading == null) {
            return RefuelMetrics(cur.id, note = "Odometer reading missing, so distance is unknown.")
        }
        val distance = curReading.subtract(prevReading)
        if (distance.signum() <= 0) {
            return RefuelMetrics(cur.id, distanceKm = distance, note = "Odometer is not higher than the previous refuelling.")
        }

        val consumption = fuelConsumed(prev, cur, tankCapacityL)
        val consumed = consumption.litres
        if (consumed == null || consumed.signum() <= 0) {
            return RefuelMetrics(
                cur.id, distanceKm = distance,
                note = if (consumed != null) "Fuel figures give a non-positive consumption; please check them." else "Fuel added is unknown.",
            )
        }

        val kmPerL = distance.divide(consumed, mc)
        val unitPrice = cur.pricePerL ?: run {
            val added = cur.fuelAddedL
            if (cur.totalCost != null && added != null && added.signum() > 0) cur.totalCost.divide(added, mc) else null
        }
        val costPerKm = unitPrice?.multiply(consumed, mc)?.divide(distance, mc)

        return RefuelMetrics(
            eventId = cur.id,
            distanceKm = distance,
            fuelConsumedL = consumed,
            kmPerL = kmPerL,
            costPerKm = costPerKm,
            quality = consumption.quality,
            method = consumption.method,
        )
    }

    data class Consumption(val litres: BigDecimal?, val method: ConsumptionMethod, val quality: CalculationQuality)

    fun fuelConsumed(prev: RefuelEvent, cur: RefuelEvent, tankCapacityL: BigDecimal?): Consumption {
        val added = cur.fuelAddedL
        val cap = tankCapacityL?.takeIf { it.signum() > 0 }
        val prevAfter = prev.fuelLevelAfterPct
        val curAfter = cur.fuelLevelAfterPct
        val curBefore = cur.fuelLevelBeforePct

        if (added != null && prev.filledToFull && cur.filledToFull) {
            return Consumption(added, ConsumptionMethod.FULL_TO_FULL, CalculationQuality.MEASURED)
        }
        if (added != null && prevAfter != null && curAfter != null) {
            val diff = curAfter.subtract(prevAfter)
            if (diff.abs() <= sameLevelTolerancePct) {
                return Consumption(added, ConsumptionMethod.SAME_LEVEL, CalculationQuality.MEASURED)
            }
            if (cap != null) {
                val adjust = diff.divide(hundred, mc).multiply(cap, mc)
                return Consumption(added.subtract(adjust), ConsumptionMethod.ADDED_ADJUSTED_BY_GAUGE, CalculationQuality.ESTIMATED)
            }
        }
        if (cap != null && prevAfter != null && curBefore != null) {
            val drop = prevAfter.subtract(curBefore)
            return Consumption(drop.divide(hundred, mc).multiply(cap, mc), ConsumptionMethod.GAUGE_ONLY, CalculationQuality.ESTIMATED)
        }
        if (added != null) {
            return Consumption(added, ConsumptionMethod.ADDED_ASSUMED_SAME_LEVEL, CalculationQuality.PARTIAL)
        }
        return Consumption(null, ConsumptionMethod.NONE, CalculationQuality.INSUFFICIENT)
    }

    /** Estimated fuel in the tank for a gauge reading. Always an estimate: gauges are not linear. */
    fun estimatedVolumeL(levelPct: BigDecimal, tankCapacityL: BigDecimal): BigDecimal =
        levelPct.divide(hundred, mc).multiply(tankCapacityL, mc)
}
