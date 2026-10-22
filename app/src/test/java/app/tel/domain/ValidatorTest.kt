package app.tel.domain

import app.tel.domain.model.Vehicle
import app.tel.domain.validation.Fields
import app.tel.domain.validation.Severity
import app.tel.domain.validation.Validator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {
    private val car = Vehicle(id = "car", name = "My Car", tankCapacityL = bd("40"))

    private fun issues(e: app.tel.domain.model.RefuelEvent, prev: app.tel.domain.model.RefuelEvent? = null) =
        Validator.validate(e, car, prev, nowMillis = Long.MAX_VALUE / 2)

    @Test fun validEventHasNoIssues() {
        assertTrue(issues(event("143140", added = "36.35", price = "112.41", total = "4086.10")).isEmpty())
    }

    @Test fun negativeValuesAreErrors() {
        val i = issues(event("-1", added = "-2", price = "-3", total = "-4"))
        assertTrue(i.count { it.severity == Severity.ERROR } >= 4)
    }

    @Test fun percentOutOfRange() {
        val i = issues(event("100", levelBefore = "120"))
        assertTrue(i.any { it.field == Fields.LEVEL_BEFORE && it.severity == Severity.ERROR })
    }

    @Test fun lowerOdometerIsWarningNotError() {
        val i = issues(event("900"), prev = event("1000"))
        assertTrue(i.any { it.field == Fields.ODO_BEFORE && it.severity == Severity.WARNING })
        assertFalse(i.any { it.severity == Severity.ERROR })
    }

    @Test fun mismatchedTotalWarns() {
        val i = issues(event("100", added = "36.35", price = "112.41", total = "4500"))
        assertTrue(i.any { it.field == Fields.TOTAL })
    }

    @Test fun overCapacityWarns() {
        assertTrue(issues(event("100", added = "60")).any { it.field == Fields.FUEL_ADDED })
    }

    @Test fun invalidCurrencyIsError() {
        assertTrue(issues(event("100", currency = "XXQ")).any { it.field == Fields.CURRENCY && it.severity == Severity.ERROR })
    }

    @Test fun consistencyAllowsDisplayRounding() {
        assertTrue(Validator.consistent(bd("36.35"), bd("112.41"), bd("4086.10")))
        assertTrue(Validator.consistent(bd("38.06"), bd("110.68"), bd("4212.48")))
        assertTrue(Validator.consistent(bd("42.18"), bd("94.75"), bd("3996.56")))
        assertFalse(Validator.consistent(bd("42.18"), bd("94.75"), bd("3988.56")))
    }
}
