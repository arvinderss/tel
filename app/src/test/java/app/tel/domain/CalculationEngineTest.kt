package app.tel.domain

import app.tel.domain.calc.CalculationEngine
import app.tel.domain.model.MileageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculationEngineTest {
    private val engine = CalculationEngine()

    @Test fun firstFillIsStartingPoint() {
        val e = event("50000", added = "30", price = "100", total = "3000")
        val m = engine.compute(listOf(e))[e.id]!!
        assertEquals(MileageStatus.FIRST, m.status)
        assertNull(m.kmPerL)
        assertNull(m.distanceSincePreviousKm)
    }

    @Test fun fullToFull() {
        val a = event("50000", added = "30", total = "3000")
        val b = event("50600", added = "40", price = "100", total = "4000")
        val m = engine.compute(listOf(a, b))[b.id]!!
        assertEquals(MileageStatus.MEASURED, m.status)
        assertClose("600", m.distanceSincePreviousKm)
        assertClose("600", m.spanDistanceKm)
        assertClose("15", m.kmPerL)
        assertClose("6.6667", m.costPerKm, "0.0001")
    }

    @Test fun partialFillsAccumulateIntoNextFullTank() {
        val a = event("10000", added = "35", total = "3500")
        val p1 = event("10200", added = "10", total = "1000", full = false)
        val p2 = event("10350", added = "5", total = "500", full = false)
        val b = event("10600", added = "25", total = "2500")
        val r = engine.compute(listOf(a, p1, p2, b))
        assertEquals(MileageStatus.PARTIAL, r[p1.id]!!.status)
        assertNull(r[p1.id]!!.kmPerL)
        assertClose("200", r[p1.id]!!.distanceSincePreviousKm)
        val m = r[b.id]!!
        // 600 km on 10 + 5 + 25 = 40 L
        assertClose("15", m.kmPerL)
        assertClose("40", m.spanFuelL)
        assertClose("4000", m.spanCost)
        assertEquals(3, m.spanFills)
        assertClose("250", m.distanceSincePreviousKm)
    }

    @Test fun partialBeforeFirstFullTankNeverAnchors() {
        val p = event("1000", added = "10", full = false)
        val a = event("1200", added = "30")
        val b = event("1500", added = "20")
        val r = engine.compute(listOf(p, a, b))
        assertEquals(MileageStatus.FIRST, r[p.id]!!.status)
        assertEquals(MileageStatus.FIRST, r[a.id]!!.status)
        assertClose("15", r[b.id]!!.kmPerL)
    }

    @Test fun missedFillStartsNewStretch() {
        val a = event("1000", added = "30")
        val gap = event("2000", added = "30", missed = true)
        val b = event("2450", added = "30")
        val r = engine.compute(listOf(a, gap, b))
        assertEquals(MileageStatus.AFTER_GAP, r[gap.id]!!.status)
        assertNull(r[gap.id]!!.distanceSincePreviousKm)
        assertClose("15", r[b.id]!!.kmPerL)
    }

    @Test fun missingVolumeInStretchIsNotMeasured() {
        val a = event("1000", added = "30")
        val p = event("1100", added = null, full = false)
        val b = event("1450", added = "30")
        val c = event("1750", added = "20")
        val r = engine.compute(listOf(a, p, b, c))
        assertEquals(MileageStatus.MISSING_DATA, r[b.id]!!.status)
        // The stretch after b is clean again.
        assertClose("15", r[c.id]!!.kmPerL)
    }

    @Test fun missingOdometerOnFullFill() {
        val a = event("1000", added = "30")
        val b = event(null, added = "30")
        val c = event("1600", added = "20")
        val r = engine.compute(listOf(a, b, c))
        assertEquals(MileageStatus.MISSING_DATA, r[b.id]!!.status)
        assertEquals(MileageStatus.FIRST, r[c.id]!!.status)
    }

    @Test fun costUnknownWhenAnAmountIsMissing() {
        val a = event("1000", added = "30", total = "3000")
        val p = event("1100", added = "10", total = null, full = false)
        val b = event("1450", added = "20", total = "2000")
        val m = engine.compute(listOf(a, p, b))[b.id]!!
        assertClose("15", m.kmPerL)
        assertNull(m.costPerKm)
    }

    @Test fun ordersByTimestampNotInputOrder() {
        val a = event("50000", added = "30", day = 1)
        val b = event("50600", added = "40", day = 10)
        val r = engine.compute(listOf(b, a))
        assertEquals(MileageStatus.FIRST, r[a.id]!!.status)
        assertClose("15", r[b.id]!!.kmPerL)
    }

    @Test fun lowerOdometerIsNotMeasured() {
        val a = event("50000", added = "30")
        val b = event("49990", added = "30")
        assertEquals(MileageStatus.MISSING_DATA, engine.compute(listOf(a, b))[b.id]!!.status)
    }
}
