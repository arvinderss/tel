package app.tel

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tel.data.CsvCopy
import app.tel.data.DataTransfer
import app.tel.data.FuelRepository
import app.tel.data.RestoreMode
import app.tel.data.SettingsRepository
import app.tel.data.TransferException
import app.tel.data.db.TelDatabase
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
class DataTransferTest {
    private lateinit var db: TelDatabase
    private lateinit var repo: FuelRepository
    private lateinit var settings: SettingsRepository
    private lateinit var transfer: DataTransfer
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() = runBlocking<Unit> {
        db = TelDatabase.inMemory(ctx)
        repo = FuelRepository(db)
        settings = SettingsRepository(db.settings())
        transfer = DataTransfer(repo)
        repo.saveVehicle(Vehicle("car", "My Car"))
        repo.saveRefuel(RefuelEvent("e1", "car", 1_000L, odometerKm = BigDecimal("143140"), fuelAddedL = BigDecimal("36.35"),
            pricePerL = BigDecimal("112.41"), totalCost = BigDecimal("4086.10"), currencyCode = "INR", notes = "Shell, NH44"))
        repo.saveRefuel(RefuelEvent("e2", "car", 2_000L, odometerKm = BigDecimal("143640"), fuelAddedL = BigDecimal("38.06"), currencyCode = "INR"))
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    @Test fun csvRoundTripReplace() = runBlocking<Unit> {
        val bytes = ByteArrayOutputStream().also { transfer.exportCsv(it) }.toByteArray()
        val r = transfer.read(bytes)
        assertEquals(1, r.vehicles.size); assertEquals(2, r.events.size)

        repo.deleteAll()
        transfer.restore(r, RestoreMode.REPLACE)
        assertEquals(2, db.refuels().count())
        val e1 = db.refuels().get("e1")!!
        assertEquals(0, BigDecimal("4086.10").compareTo(e1.totalCost))
        assertEquals("Shell, NH44", e1.notes)
        assertEquals(500.0, db.refuels().get("e2")!!.distanceSincePrevious!!.toDouble(), 0.0)
    }

    @Test fun mergeDoesNotDuplicate() = runBlocking<Unit> {
        val bytes = ByteArrayOutputStream().also { transfer.exportCsv(it) }.toByteArray()
        transfer.restore(transfer.read(bytes), RestoreMode.MERGE)
        assertEquals(2, db.refuels().count())
    }

    @Test fun rejectsOtherFiles() {
        val failed = try { transfer.read("name,email\na,b".toByteArray()); false } catch (e: TransferException) { true }
        assertTrue(failed)
    }

    @Test fun restoresZipBackupFromVersion1() = runBlocking<Unit> {
        val data = """
            {"format":1,
             "vehicles":[{"id":"v1","name":"Old Car","make":"","model":"","year":null,"fuel_type":"PETROL","tank_capacity":"40",
                          "currency_code":"INR","registration":"","created_at":1,"updated_at":1}],
             "refueling_events":[
               {"id":"o1","vehicle_id":"v1","timestamp":1000,"odometer_before":"5000","odometer_after":null,"fuel_added":"30",
                "fuel_unit":"L","price_per_unit":"100","total_cost":"3000","currency":"INR","filled_to_full":false,"notes":"","created_at":1,"updated_at":1},
               {"id":"o2","vehicle_id":"v1","timestamp":2000,"odometer_before":null,"odometer_after":"5450","fuel_added":"30",
                "fuel_unit":"L","price_per_unit":"100","total_cost":"3000","currency":"INR","filled_to_full":false,"notes":"","created_at":2,"updated_at":2}]}
        """.trimIndent()
        val bytes = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("manifest.json")); z.write("{}".toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("data.json")); z.write(data.toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("files/data/x.jpg")); z.write(byteArrayOf(1)); z.closeEntry()
            }
        }.toByteArray()
        transfer.restore(transfer.read(bytes), RestoreMode.REPLACE)
        val o2 = db.refuels().get("o2")!!
        assertEquals(0, BigDecimal("5450").compareTo(o2.odometer))
        assertTrue(o2.filledToFull)
        assertEquals(15.0, o2.mileage!!.toDouble(), 0.0001)
    }

    @Test fun linkedCsvIsUpdatedAfterEveryChange() = runBlocking<Unit> {
        val file = File(ctx.cacheDir, "tel-fillups-test.csv").apply { delete() }
        val copy = CsvCopy(ctx, repo, settings, transfer, scope)
        copy.start()
        assertTrue(copy.linkNewFile(Uri.fromFile(file)))
        assertTrue(file.readText().contains("4086.1"))

        repo.saveRefuel(RefuelEvent("e3", "car", 3_000L, odometerKm = BigDecimal("144000"), totalCost = BigDecimal("777"), currencyCode = "INR"))
        var waited = 0
        while (!file.readText().contains("144000") && waited < 5000) { delay(100); waited += 100 }
        assertTrue(file.readText().contains("144000"))
        assertTrue(settings.current().csv.lastWritten != null)

        // Deleting everything never blanks out the file.
        repo.deleteAll(keepSettings = true)
        delay(1000)
        assertTrue(file.readText().contains("144000"))

        copy.unlink()
        assertFalse(settings.current().csv.enabled)
        file.delete()
    }
}
