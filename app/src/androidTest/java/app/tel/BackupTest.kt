package app.tel

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tel.data.BackupException
import app.tel.data.BackupManager
import app.tel.data.FuelRepository
import app.tel.data.ImageStore
import app.tel.data.PhotoChange
import app.tel.data.RestoreMode
import app.tel.data.db.TelDatabase
import app.tel.domain.model.ImageType
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
            RefuelEvent("e1", "car", 1_000L, odometerBeforeKm = BigDecimal("143140"), fuelAddedL = BigDecimal("36.35"),
                pricePerL = BigDecimal("112.41"), totalCost = BigDecimal("4086.10"), currencyCode = "INR"),
            mapOf(ImageType.PUMP_METER to PhotoChange.New(draft, emptyList())),
        )
        repo.saveRefuel(RefuelEvent("e2", "car", 2_000L, odometerBeforeKm = BigDecimal("143640"), fuelAddedL = BigDecimal("38.06"), currencyCode = "INR"), emptyMap())
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

    @Test fun csvHasHeaderAndRows() = runBlocking {
        val csv = ByteArrayOutputStream().also { backup.exportCsv(it) }.toString()
        val lines = csv.trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("date_utc,vehicle"))
        assertTrue(lines[1].contains("4086.10"))
    }
}
