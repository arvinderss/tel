package app.tel.domain

import app.tel.domain.validation.Fields
import app.tel.domain.validation.Severity
import app.tel.domain.validation.Validator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {
    private fun issues(e: app.tel.domain.model.RefuelEvent, prev: app.tel.domain.model.RefuelEvent? = null, next: app.tel.domain.model.RefuelEvent? = null) =
        Validator.validate(e, prev, next, nowMillis = Long.MAX_VALUE / 2)

    @Test fun validEventHasNoIssues() {
        assertTrue(issues(event("143140", added = "36.35", price = "112.41", total = "4086.10")).isEmpty())
    }

    @Test fun emptyEntryIsError() {
        assertTrue(issues(event(null)).any { it.field == Fields.FORM && it.severity == Severity.ERROR })
    }

    @Test fun onlyAmountIsFine() {
        assertTrue(issues(event(null, total = "2000")).isEmpty())
    }

    @Test fun negativeValuesAreErrors() {
        val i = issues(event("-1", added = "-2", price = "-3", total = "-4"))
        assertTrue(i.count { it.severity == Severity.ERROR } >= 4)
    }

    @Test fun lowerOdometerThanEarlierFillIsError() {
        val i = issues(event("900"), prev = event("1000"))
        assertTrue(i.any { it.field == Fields.ODOMETER && it.severity == Severity.ERROR })
    }

    @Test fun higherOdometerThanLaterFillIsError() {
        val i = issues(event("1500"), prev = event("1000"), next = event("1400"))
        assertTrue(i.any { it.field == Fields.ODOMETER && it.severity == Severity.ERROR })
    }

    @Test fun sameOdometerIsAllowed() {
        assertFalse(issues(event("1000", total = "100"), prev = event("1000")).any { it.severity == Severity.ERROR })
    }

    @Test fun bigJumpWarnsUnlessMissedFill() {
        assertTrue(issues(event("5000"), prev = event("1000")).any { it.severity == Severity.WARNING })
        assertTrue(issues(event("5000", missed = true), prev = event("1000")).isEmpty())
    }

    @Test fun mismatchedAmountWarns() {
        val i = issues(event("100", added = "36.35", price = "112.41", total = "4500"))
        assertTrue(i.any { it.field == Fields.TOTAL && it.severity == Severity.WARNING })
    }

    @Test fun invalidCurrencyIsError() {
        assertTrue(issues(event("100", currency = "XXQ")).any { it.field == Fields.CURRENCY && it.severity == Severity.ERROR })
    }

    @Test fun consistencyAllowsDisplayRounding() {
        assertTrue(Validator.consistent(bd("36.35"), bd("112.41"), bd("4086.10")))
        assertTrue(Validator.consistent(bd("38.06"), bd("110.68"), bd("4212.48")))
        assertTrue(Validator.consistent(bd("17.79"), bd("112.41"), bd("2000")))
        assertFalse(Validator.consistent(bd("42.18"), bd("94.75"), bd("3988.56")))
    }
}
