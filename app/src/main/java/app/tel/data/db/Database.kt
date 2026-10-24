package app.tel.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

class Converters {
    @TypeConverter fun fromDecimal(v: BigDecimal?): String? = v?.toPlainString()
    @TypeConverter fun toDecimal(v: String?): BigDecimal? = v?.let { runCatching { BigDecimal(it) }.getOrNull() }
}

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY created_at")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles ORDER BY created_at")
    suspend fun all(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun get(id: String): VehicleEntity?

    @Upsert suspend fun upsert(v: VehicleEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(v: List<VehicleEntity>)

    @Query("DELETE FROM vehicles WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM vehicles") suspend fun deleteAll()
}

@Dao
interface RefuelDao {
    @Query("SELECT * FROM refueling_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RefuelEntity>>

    @Query("SELECT * FROM refueling_events ORDER BY timestamp DESC")
    suspend fun all(): List<RefuelEntity>

    @Query("SELECT * FROM refueling_events WHERE vehicle_id = :vehicleId ORDER BY timestamp")
    suspend fun forVehicle(vehicleId: String): List<RefuelEntity>

    @Query("SELECT * FROM refueling_events WHERE id = :id")
    suspend fun get(id: String): RefuelEntity?

    @Query("SELECT * FROM refueling_events WHERE id = :id")
    fun observe(id: String): Flow<RefuelEntity?>

    @Upsert suspend fun upsert(e: RefuelEntity)

    @Upsert suspend fun upsertAll(e: List<RefuelEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(e: List<RefuelEntity>)

    @Query("DELETE FROM refueling_events WHERE id = :id") suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM refueling_events") suspend fun count(): Int
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings") fun observeAll(): Flow<List<SettingEntity>>
    @Query("SELECT * FROM settings") suspend fun all(): List<SettingEntity>
    @Query("SELECT value FROM settings WHERE `key` = :key") suspend fun get(key: String): String?
    @Upsert suspend fun put(s: SettingEntity)
    @Upsert suspend fun putAll(s: List<SettingEntity>)
    @Query("DELETE FROM settings") suspend fun deleteAll()
}

@Database(
    entities = [VehicleEntity::class, RefuelEntity::class, SettingEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TelDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun refuels(): RefuelDao
    abstract fun settings(): SettingDao

    companion object {
        const val NAME = "tel.db"

        fun create(context: Context): TelDatabase =
            Room.databaseBuilder(context, TelDatabase::class.java, NAME).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        fun inMemory(context: Context): TelDatabase =
            Room.inMemoryDatabaseBuilder(context, TelDatabase::class.java).allowMainThreadQueries().build()
    }
}

/**
 * v1 → v2: photo reading removed, single odometer reading, "missed a fill-up" flag.
 *  - odometer = the old "before" reading, or the "after" reading when "before" was empty
 *  - fuel levels and OCR results are dropped
 *  - old records are marked as full-tank fills: v1 defaulted the switch to off and treated
 *    such fills as "refilled to the same level", so this keeps their mileage figures
 * Room runs migrations with foreign keys off, so the table can be rebuilt in place.
 * (Photos were kept here and removed in v3.)
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `ocr_results`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `refueling_events_new` (`id` TEXT NOT NULL, `vehicle_id` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, `odometer` TEXT, `fuel_added` TEXT, `fuel_unit` TEXT NOT NULL, " +
                "`price_per_unit` TEXT, `total_cost` TEXT, `currency` TEXT NOT NULL, `filled_to_full` INTEGER NOT NULL, " +
                "`missed_previous` INTEGER NOT NULL, `distance_since_previous` TEXT, `fuel_consumed` TEXT, `mileage` TEXT, " +
                "`cost_per_distance` TEXT, `mileage_status` TEXT, `notes` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`vehicle_id`) REFERENCES `vehicles`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `refueling_events_new` (`id`, `vehicle_id`, `timestamp`, `odometer`, `fuel_added`, `fuel_unit`, " +
                "`price_per_unit`, `total_cost`, `currency`, `filled_to_full`, `missed_previous`, `notes`, `created_at`, `updated_at`) " +
                "SELECT `id`, `vehicle_id`, `timestamp`, COALESCE(`odometer_before`, `odometer_after`), `fuel_added`, `fuel_unit`, " +
                "`price_per_unit`, `total_cost`, `currency`, 1, 0, `notes`, `created_at`, `updated_at` FROM `refueling_events`",
        )
        db.execSQL("DROP TABLE `refueling_events`")
        db.execSQL("ALTER TABLE `refueling_events_new` RENAME TO `refueling_events`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_refueling_events_vehicle_id` ON `refueling_events` (`vehicle_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_refueling_events_timestamp` ON `refueling_events` (`timestamp`)")
    }
}

/** v2 → v3: photos removed. Photo files are deleted by [app.tel.AppContainer.removeLegacyFiles]. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `images`")
    }
}
