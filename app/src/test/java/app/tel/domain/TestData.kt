package app.tel.domain

import app.tel.domain.model.RefuelEvent
import java.math.BigDecimal

fun bd(s: String) = BigDecimal(s)

private var counter = 0

fun event(
    odo: String?,
    added: String? = null,
    price: String? = null,
    total: String? = null,
    full: Boolean = true,
    missed: Boolean = false,
    day: Int = ++counter,
    vehicle: String = "car",
    currency: String = "INR",
) = RefuelEvent(
    id = "e${counter++}-$day",
    vehicleId = vehicle,
    timestamp = day * 86_400_000L,
    odometerKm = odo?.let(::bd),
    fuelAddedL = added?.let(::bd),
    pricePerL = price?.let(::bd),
    totalCost = total?.let(::bd),
    currencyCode = currency,
    filledToFull = full,
    missedPrevious = missed,
)

fun assertClose(expected: String, actual: BigDecimal?, tol: String = "0.001") {
    org.junit.Assert.assertNotNull("expected $expected but was null", actual)
    val diff = actual!!.subtract(bd(expected)).abs()
    org.junit.Assert.assertTrue("expected $expected but was ${actual.toPlainString()}", diff <= bd(tol))
}
