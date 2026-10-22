package app.tel.data

import androidx.room.withTransaction
import app.tel.data.db.ImageEntity
import app.tel.data.db.OcrResultEntity
import app.tel.data.db.TelDatabase
import app.tel.domain.calc.CalculationEngine
import app.tel.domain.model.ImageType
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import java.math.BigDecimal
import java.util.UUID

data class OcrResultInput(val field: String, val raw: String, val value: BigDecimal?, val confidence: Float)

/** A photo attached to a refuelling being saved: a new draft file, or an existing stored one. */
sealed interface PhotoChange {
    data class New(val draftFile: File, val ocr: List<OcrResultInput>) : PhotoChange
    data class KeepWithOcr(val ocr: List<OcrResultInput>) : PhotoChange
    data object Remove : PhotoChange
}

data class StoredImage(val id: String, val type: ImageType, val file: File, val relativePath: String)

class FuelRepository(
    private val db: TelDatabase,
    private val images: ImageStore,
    private val engine: CalculationEngine = CalculationEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val vehicles: Flow<List<Vehicle>> = db.vehicles().observeAll().map { l -> l.map { it.toDomain() } }
    val events: Flow<List<RefuelEvent>> = db.refuels().observeAll().map { l -> l.map { it.toDomain() } }

    /** Metrics for every event, computed per vehicle over its full history. */
    val metrics: Flow<Map<String, RefuelMetrics>> = combine(vehicles, events) { vs, es -> computeAll(vs, es) }

    fun computeAll(vs: List<Vehicle>, es: List<RefuelEvent>): Map<String, RefuelMetrics> {
        val caps = vs.associate { it.id to it.tankCapacityL }
        return es.groupBy { it.vehicleId }.flatMap { (vid, list) -> engine.compute(list, caps[vid]).entries }
            .associate { it.key to it.value }
    }

    fun observeEvent(id: String): Flow<RefuelEvent?> = db.refuels().observe(id).map { it?.toDomain() }

    fun observeImages(eventId: String): Flow<List<StoredImage>> = db.images().observeForEvent(eventId).map { l ->
        l.mapNotNull { e -> ImageType.fromKey(e.imageType)?.let { StoredImage(e.id, it, images.fileFor(e.localPath), e.localPath) } }
    }

    fun observeOcr(eventId: String): Flow<List<OcrResultEntity>> = db.ocrResults().observeForEvent(eventId)

    suspend fun vehicle(id: String): Vehicle? = db.vehicles().get(id)?.toDomain()
    suspend fun allVehicles(): List<Vehicle> = db.vehicles().all().map { it.toDomain() }
    suspend fun event(id: String): RefuelEvent? = db.refuels().get(id)?.toDomain()
    suspend fun eventsFor(vehicleId: String): List<RefuelEvent> = db.refuels().forVehicle(vehicleId).map { it.toDomain() }
    suspend fun imagesFor(eventId: String): List<StoredImage> = db.images().forEvent(eventId).mapNotNull { e ->
        ImageType.fromKey(e.imageType)?.let { StoredImage(e.id, it, images.fileFor(e.localPath), e.localPath) }
    }

    suspend fun saveVehicle(v: Vehicle): Vehicle {
        val now = clock()
        val existing = db.vehicles().get(v.id)
        val toSave = v.copy(createdAt = existing?.createdAt ?: now, updatedAt = now)
        db.vehicles().upsert(toSave.toEntity())
        recompute(v.id)
        return toSave
    }

    suspend fun deleteVehicle(id: String) {
        db.vehicles().delete(id) // cascades to events, images, OCR rows
        images.deleteVehicleDir(id)
    }

    /**
     * Saves a refuelling with its photos and OCR results, then recomputes derived values
     * for the whole vehicle history (an edit can change the next event's mileage too).
     */
    suspend fun saveRefuel(event: RefuelEvent, photos: Map<ImageType, PhotoChange>): RefuelEvent {
        val now = clock()
        val existing = db.refuels().get(event.id)
        val toSave = event.copy(createdAt = existing?.createdAt ?: now, updatedAt = now)
        // If the vehicle changed during an edit, its photos must move with it.
        val movedFrom = existing?.vehicleId?.takeIf { it != event.vehicleId }

        // Files first (outside the DB transaction), then all rows atomically.
        val committed = HashMap<ImageType, String>()
        for ((type, change) in photos) {
            if (change is PhotoChange.New) committed[type] = images.commit(change.draftFile, event.vehicleId, event.id, type)
        }
        if (movedFrom != null) {
            for (img in imagesFor(event.id)) {
                if (img.type !in committed && photos[img.type] !is PhotoChange.Remove) {
                    committed[img.type] = images.commit(img.file, event.vehicleId, event.id, img.type)
                }
            }
        }

        db.withTransaction {
            db.refuels().upsert(toSave.toEntity())
            val current = db.images().forEvent(event.id).associateBy { it.imageType }
            for (type in ImageType.entries) {
                val change = photos[type]
                val old = current[type.key]
                when {
                    change is PhotoChange.Remove -> if (old != null) db.images().delete(old.id)
                    committed[type] != null -> {
                        val id = old?.id ?: UUID.randomUUID().toString()
                        db.images().upsert(ImageEntity(id, event.id, type.key, committed.getValue(type), old?.createdAt ?: now))
                        val ocr = (change as? PhotoChange.New)?.ocr
                        if (ocr != null) replaceOcr(id, ocr, now)
                    }
                    change is PhotoChange.KeepWithOcr && old != null -> replaceOcr(old.id, change.ocr, now)
                }
            }
        }
        // Delete files of removed photos (and the old vehicle folder for a moved event).
        for ((type, change) in photos) {
            if (change is PhotoChange.Remove) images.delete("${images.relativeDir(event.vehicleId, event.id)}/${type.fileName}")
        }
        if (movedFrom != null) {
            images.deleteEventDir(movedFrom, event.id)
            recompute(movedFrom)
        }
        recompute(event.vehicleId)
        return toSave
    }

    private suspend fun replaceOcr(imageId: String, ocr: List<OcrResultInput>, now: Long) {
        db.ocrResults().deleteForImage(imageId)
        db.ocrResults().insertAll(ocr.map { OcrResultEntity(UUID.randomUUID().toString(), imageId, it.field, it.raw, it.value, it.confidence, now) })
    }

    suspend fun deleteRefuel(id: String) {
        val e = db.refuels().get(id) ?: return
        db.refuels().delete(id)
        images.deleteEventDir(e.vehicleId, id)
        recompute(e.vehicleId)
    }

    /** Deletes one photo but keeps the refuelling and its values. */
    suspend fun deleteImage(eventId: String, type: ImageType) {
        val img = db.images().forEvent(eventId).firstOrNull { it.imageType == type.key } ?: return
        db.images().delete(img.id)
        images.delete(img.localPath)
    }

    /** Persists the engine's derived values into the schema's derived columns. */
    suspend fun recompute(vehicleId: String) {
        val v = db.vehicles().get(vehicleId) ?: return
        val events = db.refuels().forVehicle(vehicleId).map { it.toDomain() }
        val m = engine.compute(events, v.tankCapacity)
        db.refuels().upsertAll(events.map { it.toEntity(m[it.id]) })
    }

    suspend fun deleteAll() {
        db.withTransaction {
            db.vehicles().deleteAll()
            db.settings().deleteAll()
        }
        images.deleteAll()
    }
}
