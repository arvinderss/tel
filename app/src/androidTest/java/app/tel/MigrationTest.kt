package app.tel

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tel.data.FuelRepository
import app.tel.data.ImageStore
import app.tel.data.db.MIGRATION_1_2
import app.tel.data.db.TelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * Builds a database exactly as version 1 of the app created it, then opens it with the current
 * Room schema. Room validates every table against the entities, so a wrong migration fails here.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "migration-test.db"

    @After fun cleanup() { ctx.deleteDatabase(name) }

    private fun createV1() {
        ctx.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name), null)
        listOf(
            "CREATE TABLE IF NOT EXISTS `vehicles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `make` TEXT NOT NULL, `model` TEXT NOT NULL, `year` INTEGER, `fuel_type` TEXT NOT NULL, `tank_capacity` TEXT, `currency_code` TEXT NOT NULL, `registration` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `refueling_events` (`id` TEXT NOT NULL, `vehicle_id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `odometer_before` TEXT, `odometer_after` TEXT, `fuel_level_before` TEXT, `fuel_level_after` TEXT, `fuel_added` TEXT, `fuel_unit` TEXT NOT NULL, `price_per_unit` TEXT, `total_cost` TEXT, `currency` TEXT NOT NULL, `filled_to_full` INTEGER NOT NULL, `distance_since_previous` TEXT, `fuel_consumed_estimate` TEXT, `mileage` TEXT, `cost_per_distance` TEXT, `calculation_quality` TEXT, `notes` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`vehicle_id`) REFERENCES `vehicles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_refueling_events_vehicle_id` ON `refueling_events` (`vehicle_id`)",
            "CREATE INDEX IF NOT EXISTS `index_refueling_events_timestamp` ON `refueling_events` (`timestamp`)",
            "CREATE TABLE IF NOT EXISTS `images` (`id` TEXT NOT NULL, `refueling_event_id` TEXT NOT NULL, `image_type` TEXT NOT NULL, `local_path` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`refueling_event_id`) REFERENCES `refueling_events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_images_refueling_event_id` ON `images` (`refueling_event_id`)",
            "CREATE TABLE IF NOT EXISTS `ocr_results` (`id` TEXT NOT NULL, `image_id` TEXT NOT NULL, `field_name` TEXT NOT NULL, `raw_text` TEXT NOT NULL, `parsed_value` TEXT, `confidence` REAL NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`image_id`) REFERENCES `images`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_ocr_results_image_id` ON `ocr_results` (`image_id`)",
            "CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "INSERT INTO vehicles VALUES ('v1','My Car','','',NULL,'PETROL','40','INR','',1,1)",
            "INSERT INTO refueling_events (id, vehicle_id, timestamp, odometer_before, odometer_after, fuel_level_before, fuel_level_after, fuel_added, fuel_unit, price_per_unit, total_cost, currency, filled_to_full, notes, created_at, updated_at) VALUES ('a','v1',1000,'143140',NULL,'15','85','36.35','L','112.41','4086.10','INR',0,'first',1,1)",
            "INSERT INTO refueling_events (id, vehicle_id, timestamp, odometer_before, odometer_after, fuel_level_before, fuel_level_after, fuel_added, fuel_unit, price_per_unit, total_cost, currency, filled_to_full, notes, created_at, updated_at) VALUES ('b','v1',2000,NULL,'143640','20','85','38.06','L','110.68','4212.48','INR',0,'',2,2)",
            "INSERT INTO images VALUES ('i1','a','pump_meter','data/vehicles/v1/transactions/a/pump.jpg',1)",
            "INSERT INTO ocr_results VALUES ('r1','i1','pump_meter.total_amount','4086.10','4086.10',0.9,1)",
            "INSERT INTO settings VALUES ('ocr_sensitivity','NORMAL')",
        ).forEach(db::execSQL)
        db.version = 1
        db.close()
    }

    @Test fun migrates1To2KeepingRecordsAndPhotos() = runBlocking {
        createV1()
        val db = Room.databaseBuilder(ctx, TelDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            val a = db.refuels().get("a")!!
            val b = db.refuels().get("b")!!
            assertEquals(0, BigDecimal("143140").compareTo(a.odometer))
            assertEquals(0, BigDecimal("143640").compareTo(b.odometer)) // fell back to the "after" reading
            assertEquals(0, BigDecimal("4086.10").compareTo(a.totalCost))
            assertTrue(a.filledToFull)
            assertEquals("first", a.notes)
            assertEquals(1, db.images().forEvent("a").size) // photo rows survive the table rebuild

            val repo = FuelRepository(db, ImageStore(ctx))
            repo.recomputeAll()
            // 500 km on 38.06 L
            assertEquals(13.137, db.refuels().get("b")!!.mileage!!.toDouble(), 0.001)
            assertNull(db.refuels().get("a")!!.mileage)

            // Foreign keys still cascade after the rebuild.
            db.vehicles().delete("v1")
            assertEquals(0, db.refuels().count())
            assertEquals(0, db.images().all().size)
        } finally {
            db.close()
        }
    }
}
