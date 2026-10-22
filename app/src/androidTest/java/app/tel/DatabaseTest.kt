package app.tel

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tel.data.FuelRepository
import app.tel.data.ImageStore
import app.tel.data.PhotoChange
import app.tel.data.db.TelDatabase
import app.tel.domain.model.CalculationQuality
import app.tel.domain.model.FuelType
import app.tel.domain.model.ImageType
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.Vehicle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: TelDatabase
    private lateinit var repo: FuelRepository
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val car = Vehicle("car", "My Car", fuelType = FuelType.PETROL, tankCapacityL = BigDecimal("40"), currencyCode = "INR")
    private val suv = Vehicle("suv", "Creta", fuelType = FuelType.DIESEL, tankCapacityL = BigDecimal("50"), currencyCode = "INR")

    private fun ev(id: String, vehicle: String, day: Long, odo: String, added: String, price: String, total: String) = RefuelEvent(
        id = id, vehicleId = vehicle, timestamp = day * 86_400_000L, odometerBeforeKm = BigDecimal(odo),
        fuelAddedL = BigDecimal(added), pricePerL = BigDecimal(price), totalCost = BigDecimal(total), currencyCode = "INR", filledToFull = true,
    )

    @Before fun setUp() = runBlocking<Unit> {
        db = TelDatabase.inMemory(ctx)
        repo = FuelRepository(db, ImageStore(ctx))
        repo.saveVehicle(car); repo.saveVehicle(suv)
    }

    @After fun tearDown() { db.close() }

    @Test fun insertQueryAndDerivedColumns() = runBlocking {
        repo.saveRefuel(ev("a", "car", 1, "143140", "36.35", "112.41", "4086.10"), emptyMap())
        repo.saveRefuel(ev("b", "car", 10, "143640", "38.06", "110.68", "4212.48"), emptyMap())
        val b = db.refuels().get("b")!!
        assertEquals(0, BigDecimal("500").compareTo(b.distanceSincePrevious))
        assertEquals(CalculationQuality.MEASURED.name, b.calculationQuality)
        // 500 km / 38.06 L
        assertEquals(13.137, b.mileage!!.toDouble(), 0.001)
        assertNull(db.refuels().get("a")!!.mileage)
        assertEquals(2, repo.events.first().size)
    }

    @Test fun updateRecomputesFollowingEvent() = runBlocking {
        repo.saveRefuel(ev("a", "car", 1, "1000", "30", "100", "3000"), emptyMap())
        repo.saveRefuel(ev("b", "car", 10, "1450", "30", "100", "3000"), emptyMap())
        repo.saveRefuel(ev("a", "car", 1, "1150", "30", "100", "3000"), emptyMap())
        assertEquals(10.0, db.refuels().get("b")!!.mileage!!.toDouble(), 0.0001)
    }

    @Test fun vehiclesAreIndependent() = runBlocking {
        repo.saveRefuel(ev("a", "car", 1, "1000", "30", "100", "3000"), emptyMap())
        repo.saveRefuel(ev("x", "suv", 2, "9000", "40", "90", "3600"), emptyMap())
        repo.saveRefuel(ev("b", "car", 10, "1300", "20", "100", "2000"), emptyMap())
        assertEquals(15.0, db.refuels().get("b")!!.mileage!!.toDouble(), 0.0001)
        assertNull(db.refuels().get("x")!!.mileage)
    }

    @Test fun deleteEventAndVehicleCascade() = runBlocking {
        val draft = File(ctx.cacheDir, "t.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        repo.saveRefuel(ev("a", "car", 1, "1000", "30", "100", "3000"), mapOf(ImageType.PUMP_METER to PhotoChange.New(draft, emptyList())))
        assertEquals(1, db.images().forEvent("a").size)
        assertTrue(repo.imagesFor("a").first().file.exists())

        repo.deleteImage("a", ImageType.PUMP_METER)
        assertEquals(0, db.images().forEvent("a").size)
        assertNotNull(db.refuels().get("a"))

        repo.deleteRefuel("a")
        assertNull(db.refuels().get("a"))

        repo.saveRefuel(ev("c", "car", 3, "1000", "30", "100", "3000"), emptyMap())
        repo.deleteVehicle("car")
        assertNull(db.refuels().get("c"))
        assertEquals(1, repo.allVehicles().size)
    }
}
