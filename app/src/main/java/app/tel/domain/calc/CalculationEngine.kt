package app.tel.domain.calc

import app.tel.domain.model.MileageStatus
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import java.math.BigDecimal
import java.math.MathContext

/**
 * Deterministic mileage engine using the full-tank-to-full-tank method. Pure Kotlin.
 *
 * Mileage is only ever measured, never estimated:
 *
 *   km/L = (odometer at full fill B − odometer at previous full fill A)
 *          ÷ (all litres added after A, up to and including B)
 *
 * Partial fills in between add their litres (and cost) to the stretch and get their figure
 * at the next full tank. A fill marked "missed previous" starts a new stretch, because fuel
 * from the unlogged fill-up is unknown. A missing odometer or volume inside a stretch makes it
 * unmeasurable; the next full fill with an odometer starts over.
 */
class CalculationEngine {
    private val mc = MathContext.DECIMAL64

    /** Chronological order; ties broken by odometer, then creation time. */
    fun chronological(events: List<RefuelEvent>): List<RefuelEvent> =
        events.sortedWith(
            compareBy<RefuelEvent> { it.timestamp }
                .thenBy { it.odometerKm ?: BigDecimal.ZERO }
                .thenBy { it.createdAt },
        )

    /** Computes metrics for all fill-ups of ONE vehicle. */
    fun compute(events: List<RefuelEvent>): Map<String, RefuelMetrics> {
        val ordered = chronological(events)
        val out = LinkedHashMap<String, RefuelMetrics>()

        var anchor: RefuelEvent? = null       // last full fill with an odometer reading
        var fuel = BigDecimal.ZERO            // litres added since the anchor
        var cost: BigDecimal? = BigDecimal.ZERO // money spent since the anchor (null once unknown)
        var fills = 0
        var broken: String? = null            // why the current stretch can't be measured
        var lastOdo: BigDecimal? = null

        fun startStretch(e: RefuelEvent?) {
            anchor = e?.takeIf { it.filledToFull && it.odometerKm != null }
            fuel = BigDecimal.ZERO; cost = BigDecimal.ZERO; fills = 0; broken = null
        }

        for ((i, e) in ordered.withIndex()) {
            val odo = e.odometerKm
            val sincePrev = if (odo != null && lastOdo != null && !e.missedPrevious) odo.subtract(lastOdo) else null
            if (odo != null) lastOdo = odo

            if (i == 0 || e.missedPrevious) {
                startStretch(e)
                out[e.id] = RefuelMetrics(
                    e.id, sincePrev,
                    status = if (i == 0) MileageStatus.FIRST else MileageStatus.AFTER_GAP,
                    note = if (e.filledToFull) "Mileage from the next full tank." else "Mileage starts from the next full tank.",
                )
                continue
            }

            // Add this fill's fuel and money to the current stretch.
            fills++
            if (e.fuelAddedL == null) broken = broken ?: "Fuel volume missing on ${if (e.filledToFull) "this" else "a partial"} fill-up."
            else fuel = fuel.add(e.fuelAddedL)
            cost = if (cost == null || e.totalCost == null) null else cost!!.add(e.totalCost)

            if (!e.filledToFull) {
                out[e.id] = RefuelMetrics(e.id, sincePrev, MileageStatus.PARTIAL, spanFills = fills,
                    note = if (anchor == null) "Mileage starts from the next full tank." else "Counted at the next full tank.")
                continue
            }

            val a = anchor
            val metrics = when {
                a == null -> RefuelMetrics(e.id, sincePrev, MileageStatus.FIRST, note = "Mileage from the next full tank.")
                odo == null -> RefuelMetrics(e.id, sincePrev, MileageStatus.MISSING_DATA, note = "Odometer reading missing.")
                broken != null -> RefuelMetrics(e.id, sincePrev, MileageStatus.MISSING_DATA, note = broken)
                else -> {
                    val distance = odo.subtract(a.odometerKm!!)
                    when {
                        distance.signum() <= 0 -> RefuelMetrics(e.id, sincePrev, MileageStatus.MISSING_DATA, note = "Odometer isn't higher than the previous full tank.")
                        fuel.signum() <= 0 -> RefuelMetrics(e.id, sincePrev, MileageStatus.MISSING_DATA, note = "No fuel recorded in this stretch.")
                        else -> RefuelMetrics(
                            eventId = e.id,
                            distanceSincePreviousKm = sincePrev,
                            status = MileageStatus.MEASURED,
                            spanDistanceKm = distance,
                            spanFuelL = fuel,
                            spanCost = cost,
                            kmPerL = distance.divide(fuel, mc),
                            costPerKm = cost?.divide(distance, mc),
                            spanFills = fills,
                        )
                    }
                }
            }
            out[e.id] = metrics
            // This full fill closes the stretch and (if it has an odometer) anchors the next one.
            startStretch(e)
        }
        return out
    }
}
