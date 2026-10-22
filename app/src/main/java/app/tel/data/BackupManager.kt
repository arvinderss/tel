package app.tel.data

import androidx.room.withTransaction
import app.tel.data.db.ImageEntity
import app.tel.data.db.OcrResultEntity
import app.tel.data.db.RefuelEntity
import app.tel.data.db.SettingEntity
import app.tel.data.db.TelDatabase
import app.tel.data.db.VehicleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupPreview(
    val createdAt: Long,
    val appVersion: String,
    val vehicles: Int,
    val transactions: Int,
    val photos: Int,
)

enum class RestoreMode { REPLACE, MERGE }

class BackupException(message: String) : Exception(message)

/**
 * Self-contained, offline backups:
 *   tel-backup.zip
 *     manifest.json   – format version, date, counts
 *     data.json       – every table
 *     files/data/...  – the photos, same relative paths as on the device
 * Nothing is uploaded; the user chooses where the file goes with the system file picker.
 */
class BackupManager(
    private val db: TelDatabase,
    private val images: ImageStore,
    private val appVersion: String,
) {
    companion object {
        const val FORMAT = 1
        private const val MANIFEST = "manifest.json"
        private const val DATA = "data.json"
        private const val FILES = "files/"
    }

    // ------------------------------------------------------------------ export

    suspend fun exportJson(out: OutputStream) = withContext(Dispatchers.IO) {
        out.write(dataJson().toString(2).toByteArray())
        out.flush()
    }

    suspend fun exportCsv(out: OutputStream) = withContext(Dispatchers.IO) {
        val vehicles = db.vehicles().all().associateBy { it.id }
        val sb = StringBuilder()
        sb.append("date_utc,vehicle,odometer_before_km,odometer_after_km,fuel_level_before_pct,fuel_level_after_pct,")
        sb.append("fuel_added_l,price_per_l,total_cost,currency,filled_to_full,distance_km,fuel_consumed_l,km_per_l,cost_per_km,calculation_quality,notes\n")
        for (e in db.refuels().all().sortedBy { it.timestamp }) {
            val cells = listOf(
                Instant.ofEpochMilli(e.timestamp).toString(),
                vehicles[e.vehicleId]?.name ?: e.vehicleId,
                e.odometerBefore.s(), e.odometerAfter.s(), e.fuelLevelBefore.s(), e.fuelLevelAfter.s(),
                e.fuelAdded.s(), e.pricePerUnit.s(), e.totalCost.s(), e.currency, e.filledToFull.toString(),
                e.distanceSincePrevious.s(), e.fuelConsumedEstimate.s(4), e.mileage.s(3), e.costPerDistance.s(4),
                e.calculationQuality ?: "", e.notes,
            )
            sb.append(cells.joinToString(",") { csv(it) }).append('\n')
        }
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    suspend fun exportBackup(out: OutputStream) = withContext(Dispatchers.IO) {
        val data = dataJson()
        val imgs = db.images().all()
        ZipOutputStream(out).use { zip ->
            val manifest = JSONObject()
                .put("app", "Tel")
                .put("format", FORMAT)
                .put("app_version", appVersion)
                .put("created_at", System.currentTimeMillis())
                .put("vehicles", data.getJSONArray("vehicles").length())
                .put("transactions", data.getJSONArray("refueling_events").length())
                .put("photos", imgs.size)
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATA)); zip.write(data.toString(2).toByteArray()); zip.closeEntry()
            for (img in imgs) {
                val f = images.fileFor(img.localPath)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry(FILES + img.localPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private suspend fun dataJson(): JSONObject {
        val o = JSONObject()
        o.put("format", FORMAT)
        o.put("vehicles", JSONArray(db.vehicles().all().map { v ->
            JSONObject().put("id", v.id).put("name", v.name).put("make", v.make).put("model", v.model)
                .put("year", v.year ?: JSONObject.NULL).put("fuel_type", v.fuelType).put("tank_capacity", v.tankCapacity.j())
                .put("currency_code", v.currencyCode).put("registration", v.registration)
                .put("created_at", v.createdAt).put("updated_at", v.updatedAt)
        }))
        o.put("refueling_events", JSONArray(db.refuels().all().map { e ->
            JSONObject().put("id", e.id).put("vehicle_id", e.vehicleId).put("timestamp", e.timestamp)
                .put("odometer_before", e.odometerBefore.j()).put("odometer_after", e.odometerAfter.j())
                .put("fuel_level_before", e.fuelLevelBefore.j()).put("fuel_level_after", e.fuelLevelAfter.j())
                .put("fuel_added", e.fuelAdded.j()).put("fuel_unit", e.fuelUnit)
                .put("price_per_unit", e.pricePerUnit.j()).put("total_cost", e.totalCost.j()).put("currency", e.currency)
                .put("filled_to_full", e.filledToFull)
                .put("distance_since_previous", e.distanceSincePrevious.j()).put("fuel_consumed_estimate", e.fuelConsumedEstimate.j())
                .put("mileage", e.mileage.j()).put("cost_per_distance", e.costPerDistance.j())
                .put("calculation_quality", e.calculationQuality ?: JSONObject.NULL)
                .put("notes", e.notes).put("created_at", e.createdAt).put("updated_at", e.updatedAt)
        }))
        o.put("images", JSONArray(db.images().all().map { i ->
            JSONObject().put("id", i.id).put("refueling_event_id", i.refuelingEventId).put("image_type", i.imageType)
                .put("local_path", i.localPath).put("created_at", i.createdAt)
        }))
        o.put("ocr_results", JSONArray(db.ocrResults().all().map { r ->
            JSONObject().put("id", r.id).put("image_id", r.imageId).put("field_name", r.fieldName).put("raw_text", r.rawText)
                .put("parsed_value", r.parsedValue.j()).put("confidence", r.confidence.toDouble()).put("created_at", r.createdAt)
        }))
        o.put("settings", JSONArray(db.settings().all().map { JSONObject().put("key", it.key).put("value", it.value) }))
        return o
    }

    // ------------------------------------------------------------------ import

    /** Reads only the manifest and data; validates structure. */
    suspend fun preview(open: () -> InputStream): BackupPreview = withContext(Dispatchers.IO) {
        var manifest: JSONObject? = null
        var data: JSONObject? = null
        var photos = 0
        runCatching {
            ZipInputStream(open()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == MANIFEST -> manifest = JSONObject(zip.readBytes().decodeToString())
                        entry.name == DATA -> data = JSONObject(zip.readBytes().decodeToString())
                        entry.name.startsWith(FILES) -> photos++
                    }
                }
            }
        }.onFailure { throw BackupException("This file isn't a Tel backup or is damaged.") }
        val m = manifest ?: throw BackupException("This file isn't a Tel backup (no manifest).")
        val d = data ?: throw BackupException("The backup has no data.")
        if (m.optString("app") != "Tel") throw BackupException("This file isn't a Tel backup.")
        if (m.optInt("format", 0) > FORMAT) throw BackupException("This backup was made by a newer version of Tel.")
        val vehicles = d.optJSONArray("vehicles") ?: throw BackupException("The backup has no vehicles table.")
        val events = d.optJSONArray("refueling_events") ?: throw BackupException("The backup has no refuelling table.")
        BackupPreview(m.optLong("created_at"), m.optString("app_version"), vehicles.length(), events.length(), photos)
    }

    /** Restores a backup. REPLACE wipes current data first; MERGE adds records whose ids are new. */
    suspend fun restore(open: () -> InputStream, mode: RestoreMode, repository: FuelRepository) = withContext(Dispatchers.IO) {
        preview(open) // validate first
        val staging = File(images.fileFor(""), "restore-staging").apply { deleteRecursively(); mkdirs() }
        var data: JSONObject? = null
        try {
            ZipInputStream(open()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    when {
                        entry.name == DATA -> data = JSONObject(zip.readBytes().decodeToString())
                        entry.name.startsWith(FILES) -> {
                            val rel = entry.name.removePrefix(FILES)
                            val target = File(staging, rel).canonicalFile
                            // Refuse paths that escape the staging folder (zip-slip).
                            if (!target.path.startsWith(staging.canonicalPath + File.separator)) continue
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            val d = data ?: throw BackupException("The backup has no data.")
            val vehicles = d.getJSONArray("vehicles").objects().map { v ->
                VehicleEntity(
                    id = v.getString("id"), name = v.getString("name"), make = v.optString("make"), model = v.optString("model"),
                    year = if (v.isNull("year")) null else v.optInt("year"), fuelType = v.optString("fuel_type", "OTHER"),
                    tankCapacity = v.dec("tank_capacity"), currencyCode = v.optString("currency_code", "INR"),
                    registration = v.optString("registration"), createdAt = v.optLong("created_at"), updatedAt = v.optLong("updated_at"),
                )
            }
            val events = d.getJSONArray("refueling_events").objects().map { e ->
                RefuelEntity(
                    id = e.getString("id"), vehicleId = e.getString("vehicle_id"), timestamp = e.getLong("timestamp"),
                    odometerBefore = e.dec("odometer_before"), odometerAfter = e.dec("odometer_after"),
                    fuelLevelBefore = e.dec("fuel_level_before"), fuelLevelAfter = e.dec("fuel_level_after"),
                    fuelAdded = e.dec("fuel_added"), fuelUnit = e.optString("fuel_unit", "L"),
                    pricePerUnit = e.dec("price_per_unit"), totalCost = e.dec("total_cost"), currency = e.optString("currency", "INR"),
                    filledToFull = e.optBoolean("filled_to_full"), notes = e.optString("notes"),
                    createdAt = e.optLong("created_at"), updatedAt = e.optLong("updated_at"),
                )
            }
            val imgs = d.optJSONArray("images")?.objects().orEmpty().map { i ->
                ImageEntity(i.getString("id"), i.getString("refueling_event_id"), i.getString("image_type"), i.getString("local_path"), i.optLong("created_at"))
            }.filter { img -> !img.localPath.contains("..") }
            val ocr = d.optJSONArray("ocr_results")?.objects().orEmpty().map { r ->
                OcrResultEntity(r.getString("id"), r.getString("image_id"), r.optString("field_name"), r.optString("raw_text"),
                    r.dec("parsed_value"), r.optDouble("confidence", 0.0).toFloat(), r.optLong("created_at"))
            }
            val settings = d.optJSONArray("settings")?.objects().orEmpty().map { SettingEntity(it.getString("key"), it.getString("value")) }

            if (mode == RestoreMode.REPLACE) repository.deleteAll()
            db.withTransaction {
                db.vehicles().insertIgnore(vehicles)
                val vehicleIds = db.vehicles().all().map { it.id }.toSet()
                db.refuels().insertIgnore(events.filter { it.vehicleId in vehicleIds })
                val eventIds = db.refuels().all().map { it.id }.toSet()
                db.images().insertIgnore(imgs.filter { it.refuelingEventId in eventIds })
                val imageIds = db.images().all().map { it.id }.toSet()
                db.ocrResults().insertIgnore(ocr.filter { it.imageId in imageIds })
                if (mode == RestoreMode.REPLACE) db.settings().putAll(settings)
            }
            // Move photos into place (never overwrite an existing photo when merging).
            staging.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(staging).path
                val dest = images.fileFor(rel)
                if (mode == RestoreMode.REPLACE || !dest.exists()) {
                    dest.parentFile?.mkdirs()
                    f.copyTo(dest, overwrite = true)
                }
            }
            vehicles.forEach { repository.recompute(it.id) }
        } finally {
            staging.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun BigDecimal?.j(): Any = this?.toPlainString() ?: JSONObject.NULL
    private fun BigDecimal?.s(scale: Int? = null): String =
        this?.let { if (scale != null) it.setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString() else it.toPlainString() } ?: ""

    private fun JSONObject.dec(key: String): BigDecimal? =
        if (!has(key) || isNull(key)) null else runCatching { BigDecimal(get(key).toString()) }.getOrNull()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
