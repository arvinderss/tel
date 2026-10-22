package app.tel.domain

import app.tel.domain.calc.CalculationEngine
import app.tel.domain.calc.Period
import app.tel.domain.calc.Statistics
import app.tel.domain.calc.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class StatisticsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val engine = CalculationEngine()

    private val a = event("50000", added = "30", price = "100", total = "3000", full = true, day = 1)
    private val b = event("50600", added = "40", price = "110", total = "4400", full = true, day = 15)
    private val c = event("51050", added = "30", price = "105", total = "3150", full = true, day = 30)
    private val all = listOf(a, b, c)

    @Test fun totalsAndAverages() {
        val s = Statistics.compute(all, engine.compute(all, bd("40")), zone)
        assertEquals(3, s.refuelCount)
        assertClose("10550", s.totalSpend)
        assertClose("100", s.totalFuelL)
        assertClose("105.5", s.avgPricePerL)          // 10550 / 100
        assertClose("1050", s.totalDistanceKm)        // 600 + 450
        assertClose("15", s.avgKmPerL)                // 1050 / (40 + 30)
        assertClose("7.1905", s.avgCostPerKm, "0.0001") // (4400 + 3150) / 1050
        assertClose("110", s.highestPricePerL)
        assertClose("100", s.lowestPricePerL)
        assertClose("51050", s.latestOdometerKm)
        assertEquals(2, s.mileageSeries.size)
        assertFalse(s.includesEstimates)
    }

    @Test fun singleEventHasNoMileage() {
        val s = Statistics.compute(listOf(a), engine.compute(listOf(a), bd("40")), zone)
        assertNull(s.avgKmPerL)
        assertNull(s.avgCostPerKm)
        assertClose("3000", s.totalSpend)
    }

    @Test fun emptyIsAllNull() {
        val s = Statistics.compute(emptyList(), emptyMap(), zone)
        assertEquals(0, s.refuelCount)
        assertNull(s.totalSpend)
    }

    @Test fun multipleVehiclesAreComputedSeparately() {
        val x1 = event("1000", added = "20", full = true, vehicle = "x", day = 2)
        val x2 = event("1200", added = "10", full = true, vehicle = "x", day = 20)
        val metrics = engine.compute(all, bd("40")) + engine.compute(listOf(x1, x2), bd("50"))
        val s = Statistics.compute(all + x1 + x2, metrics, zone)
        // Car x: 200 km on 10 L. Car: 1050 km on 70 L. Combined 1250 / 80.
        assertClose("15.625", s.avgKmPerL)
        assertTrue(metrics[x1.id]!!.isFirst)
    }

    @Test fun otherCurrencyExcluded() {
        val usd = event("10", added = "10", total = "40", currency = "USD")
        val s = Statistics.compute(all + usd, engine.compute(all, null), zone, preferredCurrency = "INR")
        assertEquals("INR", s.currencyCode)
        assertEquals(1, s.excludedOtherCurrency)
    }

    @Test fun periodRanges() {
        val today = LocalDate.of(2026, 10, 23) // Friday
        val week = TimeRange.of(Period.THIS_WEEK, today, zone)
        assertEquals(LocalDate.of(2026, 10, 19).atStartOfDay(zone).toInstant().toEpochMilli(), week.startMillis)
        val month = TimeRange.of(Period.THIS_MONTH, today, zone)
        assertTrue(LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli() in month)
        assertFalse(LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant().toEpochMilli() in month)
        val lastYear = TimeRange.of(Period.LAST_YEAR, today, zone)
        assertTrue(LocalDate.of(2025, 6, 1).atStartOfDay(zone).toInstant().toEpochMilli() in lastYear)
        assertFalse(LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() in lastYear)
        val custom = TimeRange.of(Period.CUSTOM, today, zone, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
        assertTrue(LocalDate.of(2026, 1, 31).atTime(23, 0).toInstant(ZoneOffset.UTC).toEpochMilli() in custom)
        assertFalse(LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli() in custom)
        assertTrue(0L in TimeRange.of(Period.ALL_TIME, today, zone))
    }
}
