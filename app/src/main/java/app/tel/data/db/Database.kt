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
interface ImageDao {
    @Query("SELECT * FROM images WHERE refueling_event_id = :eventId")
    suspend fun forEvent(eventId: String): List<ImageEntity>

    @Query("SELECT * FROM images WHERE refueling_event_id = :eventId")
    fun observeForEvent(eventId: String): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images") suspend fun all(): List<ImageEntity>

    @Upsert suspend fun upsert(i: ImageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(i: List<ImageEntity>)

    @Query("DELETE FROM images WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM images WHERE refueling_event_id = :eventId AND image_type = :type")
    suspend fun deleteForEventAndType(eventId: String, type: String)
}

@Dao
interface OcrResultDao {
    @Query("SELECT * FROM ocr_results WHERE image_id IN (SELECT id FROM images WHERE refueling_event_id = :eventId)")
    suspend fun forEvent(eventId: String): List<OcrResultEntity>

    @Query("SELECT * FROM ocr_results WHERE image_id IN (SELECT id FROM images WHERE refueling_event_id = :eventId)")
    fun observeForEvent(eventId: String): Flow<List<OcrResultEntity>>

    @Query("SELECT * FROM ocr_results") suspend fun all(): List<OcrResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(r: List<OcrResultEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(r: List<OcrResultEntity>)

    @Query("DELETE FROM ocr_results WHERE image_id = :imageId") suspend fun deleteForImage(imageId: String)
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
    entities = [VehicleEntity::class, RefuelEntity::class, ImageEntity::class, OcrResultEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TelDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun refuels(): RefuelDao
    abstract fun images(): ImageDao
    abstract fun ocrResults(): OcrResultDao
    abstract fun settings(): SettingDao

    companion object {
        const val NAME = "tel.db"

        fun create(context: Context): TelDatabase =
            Room.databaseBuilder(context, TelDatabase::class.java, NAME).build()

        fun inMemory(context: Context): TelDatabase =
            Room.inMemoryDatabaseBuilder(context, TelDatabase::class.java).allowMainThreadQueries().build()
    }
}
