package app.tel.data

import androidx.room.withTransaction
import app.tel.data.db.ImageEntity
import app.tel.data.db.TelDatabase
import app.tel.domain.calc.CalculationEngine
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/** A photo attached to a fill-up. */
data class StoredImage(val id: String, val file: File, val relativePath: String, val createdAt: Long)

class FuelRepository(
    private val db: TelDatabase,
    private val images: ImageStore,
    private val engine: CalculationEngine = CalculationEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val vehicles: Flow<List<Vehicle>> = db.vehicles().observeAll().map { l -> l.map { it.toDomain() } }
    val events: Flow<List<RefuelEvent>> = db.refuels().observeAll().map { l -> l.map { it.toDomain() } }

    /** Metrics for every fill-up, computed per vehicle over its full history. */
    val metrics: Flow<Map<String, RefuelMetrics>> = combine(vehicles, events) { _, es -> computeAll(es) }

    fun computeAll(es: List<RefuelEvent>): Map<String, RefuelMetrics> =
        es.groupBy { it.vehicleId }.flatMap { (_, list) -> engine.compute(list).entries }.associate { it.key to it.value }

    fun observeEvent(id: String): Flow<RefuelEvent?> = db.refuels().observe(id).map { it?.toDomain() }

    fun observeImages(eventId: String): Flow<List<StoredImage>> =
        db.images().observeForEvent(eventId).map { l -> l.map { it.toStored() }.sortedBy { it.createdAt } }

    private fun ImageEntity.toStored() = StoredImage(id, images.fileFor(localPath), localPath, createdAt)

    suspend fun vehicle(id: String): Vehicle? = db.vehicles().get(id)?.toDomain()
    suspend fun allVehicles(): List<Vehicle> = db.vehicles().all().map { it.toDomain() }
    suspend fun event(id: String): RefuelEvent? = db.refuels().get(id)?.toDomain()
    suspend fun eventsFor(vehicleId: String): List<RefuelEvent> = db.refuels().forVehicle(vehicleId).map { it.toDomain() }
    suspend fun imagesFor(eventId: String): List<StoredImage> = db.images().forEvent(eventId).map { it.toStored() }.sortedBy { it.createdAt }

    suspend fun saveVehicle(v: Vehicle): Vehicle {
        val now = clock()
        val existing = db.vehicles().get(v.id)
        val toSave = v.copy(createdAt = existing?.createdAt ?: now, updatedAt = now)
        db.vehicles().upsert(toSave.toEntity())
        return toSave
    }

    suspend fun deleteVehicle(id: String) {
        db.vehicles().delete(id) // cascades to fill-ups and photo rows
        images.deleteVehicleDir(id)
    }

    /**
     * Saves a fill-up, adds [newPhotos] (draft files), removes photos with ids in [removedPhotoIds],
     * then recomputes derived values for the vehicle's whole history (an edit can change later fills).
     */
    suspend fun saveRefuel(event: RefuelEvent, newPhotos: List<File> = emptyList(), removedPhotoIds: Set<String> = emptySet()): RefuelEvent {
        val now = clock()
        val existing = db.refuels().get(event.id)
        val toSave = event.copy(createdAt = existing?.createdAt ?: now, updatedAt = now)
        val movedFrom = existing?.vehicleId?.takeIf { it != event.vehicleId }

        // Files first (outside the DB transaction), then all rows atomically.
        val committed = newPhotos.map { images.commit(it, event.vehicleId, event.id) }
        val kept = db.images().forEvent(event.id).filter { it.id !in removedPhotoIds }
        // If the vehicle changed during an edit, its photos move with it.
        val moved = if (movedFrom != null) kept.associate { it.id to images.commit(images.fileFor(it.localPath), event.vehicleId, event.id) } else emptyMap()

        db.withTransaction {
            db.refuels().upsert(toSave.toEntity())
            for (id in removedPhotoIds) db.images().delete(id)
            for ((id, path) in moved) db.images().get(id)?.let { db.images().upsert(it.copy(localPath = path)) }
            committed.forEachIndexed { i, path ->
                db.images().upsert(ImageEntity(UUID.randomUUID().toString(), event.id, "photo", path, now + i))
            }
        }
        val referencedPaths = db.images().all().map { it.localPath }.toSet()
        if (existing != null) {
            // Delete files no longer referenced (removed photos).
            images.fileFor(images.relativeDir(event.vehicleId, event.id)).listFiles()?.forEach { f ->
                val rel = "${images.relativeDir(event.vehicleId, event.id)}/${f.name}"
                if (rel !in referencedPaths) f.delete()
            }
        }
        if (movedFrom != null) {
            images.deleteEventDir(movedFrom, event.id)
            recompute(movedFrom)
        }
        recompute(event.vehicleId)
        return toSave
    }

    suspend fun deleteRefuel(id: String) {
        val e = db.refuels().get(id) ?: return
        db.refuels().delete(id)
        images.deleteEventDir(e.vehicleId, id)
        recompute(e.vehicleId)
    }

    /** Deletes one photo but keeps the fill-up and its values. */
    suspend fun deletePhoto(imageId: String) {
        val img = db.images().get(imageId) ?: return
        db.images().delete(img.id)
        images.delete(img.localPath)
    }

    /** Persists the engine's derived values into the schema's derived columns (used by exports). */
    suspend fun recompute(vehicleId: String) {
        val events = db.refuels().forVehicle(vehicleId).map { it.toDomain() }
        val m = engine.compute(events)
        db.refuels().upsertAll(events.map { it.toEntity(m[it.id]) })
    }

    suspend fun recomputeAll() {
        db.vehicles().all().forEach { recompute(it.id) }
    }

    suspend fun deleteAll() {
        db.withTransaction {
            db.vehicles().deleteAll()
            db.settings().deleteAll()
        }
        images.deleteAll()
    }
}
