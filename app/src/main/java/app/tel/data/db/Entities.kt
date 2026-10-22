package app.tel.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

/*
 * SQLite schema. Decimal values are stored as TEXT (exact BigDecimal strings) so money and
 * measurements never pick up floating-point error. Measurements are canonical: km, litres, per-litre.
 */

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val make: String,
    val model: String,
    val year: Int?,
    @ColumnInfo(name = "fuel_type") val fuelType: String,
    @ColumnInfo(name = "tank_capacity") val tankCapacity: BigDecimal?,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    val registration: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "refueling_events",
    foreignKeys = [ForeignKey(entity = VehicleEntity::class, parentColumns = ["id"], childColumns = ["vehicle_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("vehicle_id"), Index("timestamp")],
)
data class RefuelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    val timestamp: Long,
    @ColumnInfo(name = "odometer_before") val odometerBefore: BigDecimal?,
    @ColumnInfo(name = "odometer_after") val odometerAfter: BigDecimal?,
    @ColumnInfo(name = "fuel_level_before") val fuelLevelBefore: BigDecimal?,
    @ColumnInfo(name = "fuel_level_after") val fuelLevelAfter: BigDecimal?,
    @ColumnInfo(name = "fuel_added") val fuelAdded: BigDecimal?,
    /** Always "L": values are stored in litres and converted for display. */
    @ColumnInfo(name = "fuel_unit") val fuelUnit: String = "L",
    @ColumnInfo(name = "price_per_unit") val pricePerUnit: BigDecimal?,
    @ColumnInfo(name = "total_cost") val totalCost: BigDecimal?,
    val currency: String,
    @ColumnInfo(name = "filled_to_full") val filledToFull: Boolean,
    // Derived values: recomputed by the calculation engine whenever this vehicle's history changes.
    @ColumnInfo(name = "distance_since_previous") val distanceSincePrevious: BigDecimal? = null,
    @ColumnInfo(name = "fuel_consumed_estimate") val fuelConsumedEstimate: BigDecimal? = null,
    val mileage: BigDecimal? = null,
    @ColumnInfo(name = "cost_per_distance") val costPerDistance: BigDecimal? = null,
    @ColumnInfo(name = "calculation_quality") val calculationQuality: String? = null,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "images",
    foreignKeys = [ForeignKey(entity = RefuelEntity::class, parentColumns = ["id"], childColumns = ["refueling_event_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("refueling_event_id")],
)
data class ImageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "refueling_event_id") val refuelingEventId: String,
    /** before_hud | after_hud | pump_meter */
    @ColumnInfo(name = "image_type") val imageType: String,
    /** Path relative to the app's private files directory. */
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "ocr_results",
    foreignKeys = [ForeignKey(entity = ImageEntity::class, parentColumns = ["id"], childColumns = ["image_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("image_id")],
)
data class OcrResultEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "image_id") val imageId: String,
    @ColumnInfo(name = "field_name") val fieldName: String,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "parsed_value") val parsedValue: BigDecimal?,
    val confidence: Float,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
