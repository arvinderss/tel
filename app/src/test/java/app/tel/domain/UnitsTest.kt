package app.tel.domain

import app.tel.domain.model.UnitSystem
import app.tel.domain.units.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {
    @Test fun usGallonNotImperial() {
        assertClose("1", Units.litresToDisplay(bd("3.785411784"), UnitSystem.US), "0.0000001")
        assertClose("10.566882", Units.litresToDisplay(bd("40"), UnitSystem.US), "0.000001")
    }

    @Test fun miles() {
        assertClose("100", Units.kmToDisplay(bd("160.9344"), UnitSystem.US), "0.0000001")
        assertClose("160.9344", Units.displayToKm(bd("100"), UnitSystem.US), "0.0000001")
    }

    @Test fun mpgFromKmPerLitre() {
        // 10 km/L = 23.5215 MPG (US)
        assertClose("23.5215", Units.economyToDisplay(bd("10"), UnitSystem.US), "0.0001")
        assertClose("10", Units.economyToDisplay(bd("10"), UnitSystem.METRIC))
    }

    @Test fun litresPer100() {
        assertClose("6.6667", Units.litresPer100Km(bd("15")), "0.0001")
        assertNull(Units.litresPer100Km(bd("0")))
    }

    @Test fun pricePerGallon() {
        assertClose("378.5411784", Units.pricePerLToDisplay(bd("100"), UnitSystem.US), "0.0000001")
        assertClose("100", Units.displayPriceToPerL(bd("378.5411784"), UnitSystem.US), "0.0000001")
    }

    @Test fun costPerMile() {
        assertClose("16.09344", Units.costPerKmToDisplay(bd("10"), UnitSystem.US), "0.0000001")
    }

    @Test fun metricIsIdentity() {
        val v = bd("123.456")
        assertEquals(v, Units.kmToDisplay(v, UnitSystem.METRIC))
        assertEquals(v, Units.litresToDisplay(v, UnitSystem.METRIC))
    }

    @Test fun roundTripKeepsPrecision() {
        val l = bd("42.18")
        val back = Units.displayToLitres(Units.litresToDisplay(l, UnitSystem.US), UnitSystem.US)
        assertClose("42.18", back, "0.000000001")
    }
}
