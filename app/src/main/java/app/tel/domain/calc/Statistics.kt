package app.tel.domain.calc

import app.tel.domain.model.MileageStatus
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import java.math.BigDecimal
import java.math.MathContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class Period(val label: String) {
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LAST_3_MONTHS("Last 3 months"),
    LAST_6_MONTHS("Last 6 months"),
    THIS_YEAR("This year"),
    LAST_YEAR("Last year"),
    ALL_TIME("All time"),
    CUSTOM("Custom range");
}

/** Half-open time range [startMillis, endMillis). Null bounds are open. */
data class TimeRange(val startMillis: Long?, val endMillis: Long?) {
    operator fun contains(t: Long): Boolean =
        (startMillis == null || t >= startMillis) && (endMillis == null || t < endMillis)

    companion object {
        val ALL = TimeRange(null, null)

        fun of(period: Period, today: LocalDate, zone: ZoneId, customStart: LocalDate? = null, customEnd: LocalDate? = null): TimeRange {
            fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val tomorrow = ms(today.plusDays(1))
            return when (period) {
                Period.THIS_WEEK -> TimeRange(ms(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))), tomorrow)
                Period.THIS_MONTH -> TimeRange(ms(today.withDayOfMonth(1)), tomorrow)
                Period.LAST_3_MONTHS -> TimeRange(ms(today.minusMonths(3)), tomorrow)
                Period.LAST_6_MONTHS -> TimeRange(ms(today.minusMonths(6)), tomorrow)
                Period.THIS_YEAR -> TimeRange(ms(today.withDayOfYear(1)), tomorrow)
                Period.LAST_YEAR -> TimeRange(ms(today.minusYears(1).withDayOfYear(1)), ms(today.withDayOfYear(1)))
                Period.ALL_TIME -> ALL
                Period.CUSTOM -> TimeRange(customStart?.let(::ms), customEnd?.let { ms(it.plusDays(1)) })
            }
        }
    }
}

data class ChartPoint(val timestamp: Long, val value: BigDecimal)
data class MonthlySpend(val month: YearMonth, val amount: BigDecimal)

/** Dashboard figures. Every field is null when there is not enough data for it. */
data class DashboardStats(
    val currencyCode: String,
    val refuelCount: Int,
    val totalSpend: BigDecimal?,
    val totalFuelL: BigDecimal?,
    val totalDistanceKm: BigDecimal?,
    val avgPricePerL: BigDecimal?,
    val avgKmPerL: BigDecimal?,
    val avgCostPerKm: BigDecimal?,
    val avgFillL: BigDecimal?,
    val avgSpendPerFill: BigDecimal?,
    val highestPricePerL: BigDecimal?,
    val lowestPricePerL: BigDecimal?,
    val bestKmPerL: BigDecimal?,
    val worstKmPerL: BigDecimal?,
    val estimatedAnnualCost: BigDecimal?,
    val latestOdometerKm: BigDecimal?,
    val lastEvent: RefuelEvent?,
    val mileageSeries: List<ChartPoint>,
    val priceSeries: List<ChartPoint>,
    val monthlySpend: List<MonthlySpend>,
    /** Events skipped because they use a different currency from [currencyCode]. */
    val excludedOtherCurrency: Int,
)

object Statistics {
    private val mc = MathContext.DECIMAL64

    /**
     * @param events events already filtered to the period (may span vehicles).
     * @param metrics metrics for those events (computed per vehicle over full history).
     * @param latestOdometerKm latest known odometer (not period-limited).
     */
    fun compute(
        events: List<RefuelEvent>,
        metrics: Map<String, RefuelMetrics>,
        zone: ZoneId,
        latestOdometerKm: BigDecimal? = null,
        preferredCurrency: String? = null,
    ): DashboardStats {
        val currency = preferredCurrency?.takeIf { c -> events.any { it.currencyCode == c } }
            ?: events.groupingBy { it.currencyCode }.eachCount().maxByOrNull { it.value }?.key
            ?: preferredCurrency ?: "INR"
        val evs = events.filter { it.currencyCode == currency }.sortedBy { it.timestamp }
        val excluded = events.size - evs.size

        val costs = evs.mapNotNull { it.totalCost }
        val totalSpend = costs.sumOrNull()
        val totalFuel = evs.mapNotNull { it.fuelAddedL }.sumOrNull()

        // Average price: total spend / total fuel, over events where both are known.
        val priced = evs.filter { it.totalCost != null && it.fuelAddedL != null && it.fuelAddedL.signum() > 0 }
        val avgPrice = ratio(priced.mapNotNull { it.totalCost }.sumOrNull(), priced.mapNotNull { it.fuelAddedL }.sumOrNull())

        val ms = evs.mapNotNull { metrics[it.id] }
        val totalDistance = ms.mapNotNull { it.distanceSincePreviousKm?.takeIf { d -> d.signum() > 0 } }.sumOrNull()

        // Mileage and cost per km come only from measured full-to-full stretches.
        val measured = ms.filter { it.status == MileageStatus.MEASURED && it.spanDistanceKm != null && it.spanFuelL != null }
        val avgKmPerL = ratio(measured.mapNotNull { it.spanDistanceKm }.sumOrNull(), measured.mapNotNull { it.spanFuelL }.sumOrNull())
        val costed = measured.filter { it.spanCost != null }
        val avgCostPerKm = ratio(costed.mapNotNull { it.spanCost }.sumOrNull(), costed.mapNotNull { it.spanDistanceKm }.sumOrNull())
        val prices = evs.mapNotNull { unitPrice(it) }
        val economies = measured.mapNotNull { it.kmPerL }

        val first = evs.firstOrNull()?.timestamp
        val last = evs.lastOrNull()?.timestamp
        val spanDays = if (first != null && last != null) (last - first) / 86_400_000.0 else 0.0
        // Spend in the window excluding the first fill (it bought fuel used before the window), scaled to a year.
        val annual = if (spanDays >= 28 && evs.size >= 3) {
            evs.drop(1).mapNotNull { it.totalCost }.sumOrNull()
                ?.multiply(BigDecimal(365), mc)?.divide(BigDecimal(spanDays), mc)
        } else null

        val monthly = evs.filter { it.totalCost != null }
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) }
            .map { (m, list) -> MonthlySpend(m, list.mapNotNull { it.totalCost }.fold(BigDecimal.ZERO, BigDecimal::add)) }
            .sortedBy { it.month }

        return DashboardStats(
            currencyCode = currency,
            refuelCount = evs.size,
            totalSpend = totalSpend,
            totalFuelL = totalFuel,
            totalDistanceKm = totalDistance,
            avgPricePerL = avgPrice,
            avgKmPerL = avgKmPerL,
            avgCostPerKm = avgCostPerKm,
            avgFillL = ratio(totalFuel, evs.count { it.fuelAddedL != null }.takeIf { it > 0 }?.let { BigDecimal(it) }),
            avgSpendPerFill = ratio(totalSpend, costs.size.takeIf { it > 0 }?.let { BigDecimal(it) }),
            highestPricePerL = prices.maxOrNull(),
            lowestPricePerL = prices.minOrNull(),
            bestKmPerL = economies.maxOrNull(),
            worstKmPerL = economies.minOrNull(),
            estimatedAnnualCost = annual,
            latestOdometerKm = latestOdometerKm ?: evs.mapNotNull { it.odometerKm }.maxOrNull(),
            lastEvent = evs.lastOrNull(),
            mileageSeries = evs.mapNotNull { e -> metrics[e.id]?.kmPerL?.let { ChartPoint(e.timestamp, it) } },
            priceSeries = evs.mapNotNull { e -> unitPrice(e)?.let { ChartPoint(e.timestamp, it) } },
            monthlySpend = monthly,
            excludedOtherCurrency = excluded,
        )
    }

    fun unitPrice(e: RefuelEvent): BigDecimal? = e.pricePerL ?: run {
        val added = e.fuelAddedL
        if (e.totalCost != null && added != null && added.signum() > 0) e.totalCost.divide(added, mc) else null
    }

    private fun List<BigDecimal>.sumOrNull(): BigDecimal? = if (isEmpty()) null else fold(BigDecimal.ZERO, BigDecimal::add)

    private fun ratio(a: BigDecimal?, b: BigDecimal?): BigDecimal? =
        if (a == null || b == null || b.signum() == 0) null else a.divide(b, mc)
}
