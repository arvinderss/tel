package app.tel.domain

import app.tel.domain.calc.CalculationEngine
import app.tel.domain.model.CalculationQuality
import app.tel.domain.model.ConsumptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculationEngineTest {
    private val engine = CalculationEngine()
    private val tank = bd("40")

    @Test fun firstTransactionHasNoMileage() {
        val e = event("50000", added = "30", price = "100", total = "3000")
        val m = engine.compute(listOf(e), tank)[e.id]!!
        assertTrue(m.isFirst)
        assertNull(m.kmPerL)
        assertNull(m.distanceKm)
        assertEquals(CalculationQuality.INSUFFICIENT, m.quality)
    }

    @Test fun distanceIsCurrentMinusPrevious() {
        val a = event("50000", added = "30", full = true)
        val b = event("50600", added = "40", full = true)
        val m = engine.compute(listOf(a, b), tank)[b.id]!!
        assertClose("600", m.distanceKm)
    }

    @Test fun fullToFullIsMeasured() {
        val a = event("50000", added = "30", full = true)
        val b = event("50600", added = "40", price = "100", total = "4000", full = true)
        val m = engine.compute(listOf(a, b), tank)[b.id]!!
        assertClose("15", m.kmPerL)
        assertClose("6.6667", m.costPerKm, "0.0001")
        assertEquals(CalculationQuality.MEASURED, m.quality)
        assertEquals(ConsumptionMethod.FULL_TO_FULL, m.method)
    }

    @Test fun sameGaugeLevelIsMeasured() {
        val a = event("50000", added = "30", levelBefore = "10", levelAfter = "90")
        val b = event("50500", added = "25", levelBefore = "25", levelAfter = "91")
        val m = engine.compute(listOf(a, b), tank)[b.id]!!
        assertEquals(ConsumptionMethod.SAME_LEVEL, m.method)
        assertClose("20", m.kmPerL)
    }

    @Test fun specExampleAdjustedByGaugeIsEstimated() {
        // Previous: 50,000 km, 20 % after. Current: 50,500 km, 15 % -> 85 %, 42 L added, 40 L tank.
        val a = event("50000", added = "10", levelAfter = "20")
        val b = event("50500", added = "42", levelBefore = "15", levelAfter = "85")
        val m = engine.compute(listOf(a, b), tank)[b.id]!!
        // Tank rose from 20 % to 85 % between leaving fill A and leaving fill B: 65 % of 40 L = 26 L kept.
        // Consumed = 42 - 26 = 16 L -> 31.25 km/L.
        assertEquals(ConsumptionMethod.ADDED_ADJUSTED_BY_GAUGE, m.method)
        assertEquals(CalculationQuality.ESTIMATED, m.quality)
        assertClose("16", m.fuelConsumedL)
        assertClose("31.25", m.kmPerL)
    }

    @Test fun gaugeOnlyWhenFuelAddedUnknown() {
        val a = event("50000", levelAfter = "80")
        val b = event("50300", levelBefore = "30", levelAfter = "90")
        val m = engine.compute(listOf(a, b), tank)[b.id]!!
        assertEquals(ConsumptionMethod.GAUGE_ONLY, m.method)
        assertClose("20", m.fuelConsumedL) // 50 % of 40 L
        assertClose("15", m.kmPerL)
    }

    @Test fun onlyFuelAddedIsPartial() {
        val a = event("50000", added = "30")
        val b = event("50450", added = "30")
        val m = engine.compute(listOf(a, b), tank)[b.id]!!
        assertEquals(CalculationQuality.PARTIAL, m.quality)
        assertClose("15", m.kmPerL)
    }

    @Test fun missingOdometerIsInsufficient() {
        val a = event(null, added = "30")
        val b = event("50450", added = "30")
        val m = engine.compute(listOf(a, b), tank)[b.id]!!
        assertNull(m.kmPerL)
        assertEquals(CalculationQuality.INSUFFICIENT, m.quality)
    }

    @Test fun missingFuelIsInsufficientButDistanceKnown() {
        val a = event("50000", added = "30")
        val b = event("50450")
        val m = engine.compute(listOf(a, b), null)[b.id]!!
        assertClose("450", m.distanceKm)
        assertNull(m.kmPerL)
    }

    @Test fun lowerOdometerGivesNoMileage() {
        val a = event("50000", added = "30")
        val b = event("49990", added = "30")
        val m = engine.compute(listOf(a, b), tank)[b.id]!!
        assertNull(m.kmPerL)
    }

    @Test fun usesOdometerAfterOfPreviousEvent() {
        val a = event("50000", added = "30", odoAfter = "50002", full = true)
        val b = event("50602", added = "40", full = true)
        assertClose("600", engine.compute(listOf(a, b), tank)[b.id]!!.distanceKm)
    }

    @Test fun ordersByTimestampNotInputOrder() {
        val a = event("50000", added = "30", full = true, day = 1)
        val b = event("50600", added = "40", full = true, day = 10)
        val result = engine.compute(listOf(b, a), tank)
        assertTrue(result[a.id]!!.isFirst)
        assertClose("15", result[b.id]!!.kmPerL)
    }

    @Test fun costPerKmDerivedFromTotalWhenPriceMissing() {
        val a = event("1000", added = "20", full = true)
        val b = event("1300", added = "20", total = "2000", full = true)
        // 2000/20 = 100 per L; 20 L for 300 km -> 6.667 per km
        assertClose("6.6667", engine.compute(listOf(a, b), tank)[b.id]!!.costPerKm, "0.0001")
    }

    @Test fun estimatedVolumeFromPercent() {
        assertClose("18", engine.estimatedVolumeL(bd("45"), tank))
    }
}
