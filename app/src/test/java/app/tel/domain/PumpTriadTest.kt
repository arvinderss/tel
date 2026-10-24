package app.tel.domain

import app.tel.domain.calc.EntrySource
import app.tel.domain.calc.PumpField.AMOUNT
import app.tel.domain.calc.PumpField.RATE
import app.tel.domain.calc.PumpField.VOLUME
import app.tel.domain.calc.PumpTriad
import org.junit.Assert.assertEquals
import org.junit.Test

class PumpTriadTest {
    @Test fun amountAndRateGiveVolume() {
        val t = PumpTriad().edit(AMOUNT, "4086.10").edit(RATE, "112.41")
        assertEquals("36.35", t[VOLUME].text)
        assertEquals(EntrySource.CALCULATED, t[VOLUME].source)
    }

    @Test fun rateAndVolumeGiveAmount() {
        val t = PumpTriad().edit(RATE, "110.68").edit(VOLUME, "38.06")
        assertEquals("4212.48", t[AMOUNT].text)
    }

    @Test fun amountAndVolumeGiveRate() {
        val t = PumpTriad().edit(AMOUNT, "4212.48").edit(VOLUME, "38.06")
        assertEquals("110.68", t[RATE].text)
    }

    @Test fun oneValueCalculatesNothing() {
        val t = PumpTriad().edit(AMOUNT, "2000")
        assertEquals("", t[VOLUME].text)
        assertEquals("", t[RATE].text)
    }

    @Test fun prefilledRatePlusAmountGivesVolume() {
        val t = PumpTriad().prefill(RATE, bd("100")).edit(AMOUNT, "2000")
        assertEquals(EntrySource.PREFILLED, t[RATE].source)
        assertEquals("20", t[VOLUME].text)
    }

    @Test fun typingTheThirdRecalculatesTheOldest() {
        // Rate prefilled (oldest), amount typed -> volume calculated.
        // Then the user types the volume from the pump: rate (oldest) is recalculated.
        val t = PumpTriad().prefill(RATE, bd("100")).edit(AMOUNT, "2000").edit(VOLUME, "19.5")
        assertEquals(EntrySource.TYPED, t[VOLUME].source)
        assertEquals(EntrySource.CALCULATED, t[RATE].source)
        assertEquals("102.56", t[RATE].text)
        assertEquals("2000", t[AMOUNT].text)
    }

    @Test fun changingAGivenValueUpdatesTheCalculatedOne() {
        val t = PumpTriad().edit(AMOUNT, "3000").edit(RATE, "100").edit(AMOUNT, "4000")
        assertEquals("40", t[VOLUME].text)
    }

    @Test fun clearingAGivenValueClearsTheCalculatedOne() {
        val t = PumpTriad().edit(AMOUNT, "3000").edit(RATE, "100").edit(RATE, "")
        assertEquals("", t[VOLUME].text)
        assertEquals(EntrySource.EMPTY, t[VOLUME].source)
        assertEquals("3000", t[AMOUNT].text)
    }

    @Test fun overwritingTheCalculatedValueMakesItTyped() {
        val t = PumpTriad().edit(AMOUNT, "3000").edit(RATE, "100").edit(VOLUME, "30.5")
        assertEquals(EntrySource.TYPED, t[VOLUME].source)
        // Amount was the oldest typed value, so it is recalculated.
        assertEquals("3050", t[AMOUNT].text)
        assertEquals(EntrySource.CALCULATED, t[AMOUNT].source)
    }

    @Test fun zeroRateDoesNotDivide() {
        val t = PumpTriad().edit(AMOUNT, "100").edit(RATE, "0")
        assertEquals("", t[VOLUME].text)
    }

    @Test fun storedRecordKeepsAllThree() {
        val t = PumpTriad.fromStored(bd("4086.10"), bd("112.41"), bd("36.35"))
        assertEquals("4086.1", t[AMOUNT].text)
        assertEquals(EntrySource.TYPED, t[RATE].source)
        // Editing the amount recalculates the oldest of the other two (rate).
        val e = t.edit(AMOUNT, "4100")
        assertEquals(EntrySource.CALCULATED, e[RATE].source)
        assertEquals("112.79", e[RATE].text)
    }
}
