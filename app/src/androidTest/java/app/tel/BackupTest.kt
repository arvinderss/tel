package app.tel

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tel.data.BackupException
import app.tel.data.BackupManager
import app.tel.data.FuelRepository
import app.tel.data.ImageStore
import app.tel.data.RestoreMode
import app.tel.data.db.TelDatabase
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.Vehicle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private lateinit var db: TelDatabase
    private lateinit var repo: FuelRepository
    private lateinit var backup: BackupManager
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setUp() = runBlocking<Unit> {
        db = TelDatabase.inMemory(ctx)
        val store = ImageStore(ctx)
        repo = FuelRepository(db, store)
        backup = BackupManager(db, store, "test")
        repo.deleteAll()
        repo.saveVehicle(Vehicle("car", "My Car", tankCapacityL = BigDecimal("40")))
        val draft = File(ctx.cacheDir, "pump.jpg").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        repo.saveRefuel(
            RefuelEvent("e1", "car", 1_000L, odometerKm = BigDecimal("143140"), fuelAddedL = BigDecimal("36.35"),
                pricePerL = BigDecimal("112.41"), totalCost = BigDecimal("4086.10"), currencyCode = "INR"),
            listOf(draft),
        )
        repo.saveRefuel(RefuelEvent("e2", "car", 2_000L, odometerKm = BigDecimal("143640"), fuelAddedL = BigDecimal("38.06"), currencyCode = "INR"))
    }

    @After fun tearDown() = runBlocking<Unit> { repo.deleteAll(); db.close() }

    @Test fun backupRestoreRoundTrip() = runBlocking {
        val bytes = ByteArrayOutputStream().also { backup.exportBackup(it) }.toByteArray()
        val p = backup.preview { bytes.inputStream() }
        assertEquals(1, p.vehicles); assertEquals(2, p.transactions); assertEquals(1, p.photos)

        repo.deleteAll()
        assertEquals(0, db.refuels().count())

        backup.restore({ bytes.inputStream() }, RestoreMode.REPLACE, repo)
        assertEquals(2, db.refuels().count())
        assertEquals(0, BigDecimal("4086.10").compareTo(db.refuels().get("e1")!!.totalCost))
        assertTrue(repo.imagesFor("e1").first().file.exists())
        assertEquals(500.0, db.refuels().get("e2")!!.distanceSincePrevious!!.toDouble(), 0.0)
    }

    @Test fun mergeDoesNotDuplicate() = runBlocking {
        val bytes = ByteArrayOutputStream().also { backup.exportBackup(it) }.toByteArray()
        backup.restore({ bytes.inputStream() }, RestoreMode.MERGE, repo)
        assertEquals(2, db.refuels().count())
    }

    @Test(expected = BackupException::class)
    fun rejectsNonBackup() { runBlocking { backup.preview { "not a zip".byteInputStream() } } }

    @Test fun restoresFormat1Backup() = runBlocking {
        // A backup written by the first version: before/after odometer, fuel levels, OCR rows.
        val data = """
            {"format":1,
             "vehicles":[{"id":"v1","name":"Old Car","make":"","model":"","year":null,"fuel_type":"PETROL","tank_capacity":"40",
                          "currency_code":"INR","registration":"","created_at":1,"updated_at":1}],
             "refueling_events":[
               {"id":"o1","vehicle_id":"v1","timestamp":1000,"odometer_before":"5000","odometer_after":null,"fuel_level_before":"10",
                "fuel_level_after":"90","fuel_added":"30","fuel_unit":"L","price_per_unit":"100","total_cost":"3000","currency":"INR",
                "filled_to_full":false,"notes":"","created_at":1,"updated_at":1},
               {"id":"o2","vehicle_id":"v1","timestamp":2000,"odometer_before":null,"odometer_after":"5450","fuel_level_before":"20",
                "fuel_level_after":"90","fuel_added":"30","fuel_unit":"L","price_per_unit":"100","total_cost":"3000","currency":"INR",
                "filled_to_full":false,"notes":"","created_at":2,"updated_at":2}],
             "images":[], "ocr_results":[{"id":"r","image_id":"x","field_name":"f","raw_text":"1","parsed_value":"1","confidence":0.5,"created_at":1}],
             "settings":[{"key":"ocr_sensitivity","value":"NORMAL"},{"key":"onboarded","value":"true"}]}
        """.trimIndent()
        val manifest = """{"app":"Tel","format":1,"app_version":"1.0.8","created_at":1,"vehicles":1,"transactions":2,"photos":0}"""
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("data.json")); z.write(data.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        backup.restore({ bytes.inputStream() }, RestoreMode.REPLACE, repo)
        val o2 = db.refuels().get("o2")!!
        assertEquals(0, BigDecimal("5450").compareTo(o2.odometer))
        assertTrue(o2.filledToFull)
        assertEquals(15.0, o2.mileage!!.toDouble(), 0.0001)
    }

    @Test fun csvHasHeaderAndRows() = runBlocking {
        val csv = ByteArrayOutputStream().also { backup.exportCsv(it) }.toString()
        val lines = csv.trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("date_utc,vehicle,odometer_km,amount"))
        assertTrue(lines[1].contains("4086.10"))
    }
}
