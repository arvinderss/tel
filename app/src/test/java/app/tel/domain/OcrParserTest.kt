package app.tel.domain

import app.tel.domain.ocr.Box
import app.tel.domain.ocr.HudParser
import app.tel.domain.ocr.NumberExtractor
import app.tel.domain.ocr.OcrField
import app.tel.domain.ocr.OcrLine
import app.tel.domain.ocr.PumpMeterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures mirror what on-device OCR returns for three real photos:
 * pump-meter-1.jpg (stacked LCD, labels under each window), pump-meter-2.jpg (Tokheim,
 * amount and volume in one window, density and rate side by side) and odometer-1.jpg.
 */
class OcrParserTest {
    private fun lines(vararg text: String, variant: String = "original") =
        text.mapIndexed { i, t -> OcrLine(t, null, variant, i) }

    // ---------------- number extraction ----------------

    @Test fun normalizesCommonFormats() {
        assertEquals(bd("3988.56"), NumberExtractor.normalize("3,988.56"))
        assertEquals(bd("3988.56"), NumberExtractor.normalize("3.988,56"))
        assertEquals(bd("42.18"), NumberExtractor.normalize("42,18"))
        assertEquals(bd("52431"), NumberExtractor.normalize("52,431"))
        assertEquals(bd("143140"), NumberExtractor.normalize("143 140"))
        assertEquals(bd("4086.10"), NumberExtractor.normalize("4O86.1O"))
        assertEquals(bd("112.41"), NumberExtractor.normalize("1l2.4I"))
        assertNull(NumberExtractor.normalize("Litres"))
    }

    @Test fun extractsSuffixAndSkipsWords() {
        val t = NumberExtractor.extract(OcrLine("ODO 143 140km"))
        assertEquals(bd("143140"), t.first { !it.synthetic }.value)
        assertTrue(t.first { !it.synthetic }.suffix.startsWith("km"))
        assertTrue(NumberExtractor.extract(OcrLine("Density in Kg / m3")).none { it.value.toInt() > 3 })
    }

    @Test fun splitsTwoValuesOnOneLine() {
        val v = NumberExtractor.extract(OcrLine("756.8 110.68")).filter { !it.synthetic }.map { it.value }
        assertTrue(v.contains(bd("756.8")))
        assertTrue(v.contains(bd("110.68")))
    }

    @Test fun joinsSegmentGaps() {
        val v = NumberExtractor.extract(OcrLine("1 1 2.41")).filter { !it.synthetic }.map { it.value }
        assertEquals(listOf(bd("112.41")), v)
    }

    // ---------------- pump meter ----------------

    @Test fun pumpMeterStackedLayoutIgnoresDensity() {
        // pump-meter-1.jpg
        val r = PumpMeterParser.parse(lines(
            "Ensure Zero Before Delivery", "4086.10", "₹", "36.35", "Litres", "112.41", "₹/Lit", "753.5",
            "Out of Limit", "Density in Kg / m3", "Within Limit",
        ))
        assertEquals(bd("4086.10"), r[OcrField.TOTAL_AMOUNT]!!.best!!.value)
        assertEquals(bd("36.35"), r[OcrField.FUEL_QUANTITY]!!.best!!.value)
        assertEquals(bd("112.41"), r[OcrField.PRICE_PER_UNIT]!!.best!!.value)
        assertFalse(r[OcrField.TOTAL_AMOUNT]!!.needsReview)
    }

    @Test fun pumpMeterTokheimLayout() {
        // pump-meter-2.jpg
        val r = PumpMeterParser.parse(lines(
            "Amount (₹)", "4212.48", "38.06", "Volume (L)", "756.8", "110.68", "Density (Kg/m3)", "Rate (₹/L)", "XP95",
            "ENSURE REGISTER SET TO ZERO BEFORE DELIVERY", "1 2 3 4 5 6 7 8 9 0",
        ))
        assertEquals(bd("4212.48"), r[OcrField.TOTAL_AMOUNT]!!.best!!.value)
        assertEquals(bd("38.06"), r[OcrField.FUEL_QUANTITY]!!.best!!.value)
        assertEquals(bd("110.68"), r[OcrField.PRICE_PER_UNIT]!!.best!!.value)
    }

    @Test fun pumpMeterWithGeometry() {
        // Same as pump-meter-1 but with boxes, the way the app passes ML Kit output.
        fun l(t: String, top: Int, h: Int, i: Int, left: Int = 300, right: Int = 1400) = OcrLine(t, Box(left, top, right, top + h), "original", i)
        val r = PumpMeterParser.parse(listOf(
            l("4086.10", 180, 320, 0), l("₹", 570, 90, 1, 780, 850), l("36.35", 770, 320, 2),
            l("Litres", 1140, 90, 3, 740, 970), l("112.41", 1360, 200, 4, 570, 1250),
            l("₹/Lit", 1640, 90, 5, 760, 930), l("753.5", 1840, 220, 6, 690, 1250),
            l("Density in Kg / m3", 2140, 90, 7, 570, 1110),
        ))
        assertEquals(bd("4086.10"), r[OcrField.TOTAL_AMOUNT]!!.best!!.value)
        assertEquals(bd("36.35"), r[OcrField.FUEL_QUANTITY]!!.best!!.value)
        assertEquals(bd("112.41"), r[OcrField.PRICE_PER_UNIT]!!.best!!.value)
    }

    @Test fun pumpMeterMissingDecimalPoint() {
        // OCR lost the decimal point of the amount: 408610 -> 4086.10 is proposed and checked arithmetically.
        val r = PumpMeterParser.parse(lines("408610", "₹", "36.35", "Litres", "112.41", "₹/Lit"))
        assertEquals(0, bd("4086.10").compareTo(r[OcrField.TOTAL_AMOUNT]!!.best!!.value))
        assertTrue(r[OcrField.TOTAL_AMOUNT]!!.best!!.confidence < 0.9f)
    }

    @Test fun pumpMeterUsdPerGallon() {
        val r = PumpMeterParser.parse(lines("SALE $", "41.27", "GALLONS", "11.796", "PRICE PER GALLON", "3.499"))
        assertEquals(bd("41.27"), r[OcrField.TOTAL_AMOUNT]!!.best!!.value)
        assertEquals(bd("11.796"), r[OcrField.FUEL_QUANTITY]!!.best!!.value)
        assertEquals(bd("3.499"), r[OcrField.PRICE_PER_UNIT]!!.best!!.value)
    }

    @Test fun pumpMeterEuropeanCommas() {
        val r = PumpMeterParser.parse(lines("Betrag EUR", "72,45", "Liter", "40,25", "EUR/Liter", "1,799"))
        assertEquals(0, bd("72.45").compareTo(r[OcrField.TOTAL_AMOUNT]!!.best!!.value))
        assertEquals(0, bd("40.25").compareTo(r[OcrField.FUEL_QUANTITY]!!.best!!.value))
    }

    @Test fun pumpMeterInconsistentNumbersNeedReview() {
        val r = PumpMeterParser.parse(lines("Amount", "4000.00", "Volume", "36.35", "Rate", "112.41"))
        assertTrue(r[OcrField.TOTAL_AMOUNT]!!.needsReview)
        assertEquals(bd("4000.00"), r[OcrField.TOTAL_AMOUNT]!!.best!!.value)
    }

    @Test fun pumpMeterNothingReadable() {
        val r = PumpMeterParser.parse(emptyList())
        assertNotNull(r.problem)
    }

    @Test fun multipleVariantsAgree() {
        val l = lines("4086.10", "₹", "36.35", "Litres", "112.41", "₹/Lit") +
            lines("4O86.1O", "36.35", "Litres", "1l2.41", variant = "binary")
        val r = PumpMeterParser.parse(l)
        assertEquals(bd("4086.10"), r[OcrField.TOTAL_AMOUNT]!!.best!!.value)
        assertTrue(r[OcrField.TOTAL_AMOUNT]!!.best!!.confidence >= 0.8f)
    }

    // ---------------- dashboard / HUD ----------------

    @Test fun hudOdometerFromLabel() {
        // odometer-1.jpg
        val r = HudParser.parse(lines("20", "0", "---", "km/h", "ODO 143 140km", "20"))
        assertEquals(bd("143140"), r[OcrField.ODOMETER]!!.best!!.value)
        assertFalse(r[OcrField.ODOMETER]!!.needsReview)
        assertNull(r[OcrField.FUEL_LEVEL]!!.best)
    }

    @Test fun hudIgnoresTripAndSpeed() {
        val r = HudParser.parse(lines("TRIP A 345.6 km", "120 km/h", "52,431 km", "Fuel 18%", "Range 210 km"))
        assertEquals(bd("52431"), r[OcrField.ODOMETER]!!.best!!.value)
        assertEquals(bd("18"), r[OcrField.FUEL_LEVEL]!!.best!!.value)
        assertEquals(bd("210"), r[OcrField.RANGE]!!.best!!.value)
    }

    @Test fun hudFractionGauge() {
        val r = HudParser.parse(lines("FUEL 3/4", "ODO 61234"))
        assertEquals(0, bd("75").compareTo(r[OcrField.FUEL_LEVEL]!!.best!!.value))
    }

    @Test fun hudMilesSuffix() {
        val r = HudParser.parse(lines("ODO 32,118 mi", "DTE 240 mi"))
        assertEquals(bd("32118"), r[OcrField.ODOMETER]!!.best!!.value)
        assertEquals(bd("240"), r[OcrField.RANGE]!!.best!!.value)
    }

    @Test fun hudUnreadable() {
        val r = HudParser.parse(lines("---", "km/h"))
        assertNotNull(r.problem)
    }
}
