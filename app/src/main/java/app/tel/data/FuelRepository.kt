package app.tel.data

import androidx.room.withTransaction
import app.tel.data.db.TelDatabase
import app.tel.domain.calc.CalculationEngine
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class FuelRepository(
    private val db: TelDatabase,
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

    suspend fun vehicle(id: String): Vehicle? = db.vehicles().get(id)?.toDomain()
    suspend fun allVehicles(): List<Vehicle> = db.vehicles().all().map { it.toDomain() }
    suspend fun event(id: String): RefuelEvent? = db.refuels().get(id)?.toDomain()
    suspend fun eventsFor(vehicleId: String): List<RefuelEvent> = db.refuels().forVehicle(vehicleId).map { it.toDomain() }

    suspend fun saveVehicle(v: Vehicle): Vehicle {
        val now = clock()
        val existing = db.vehicles().get(v.id)
        val toSave = v.copy(createdAt = existing?.createdAt ?: now, updatedAt = now)
        db.vehicles().upsert(toSave.toEntity())
        return toSave
    }

    suspend fun deleteVehicle(id: String) {
        db.vehicles().delete(id) // cascades to fill-ups
    }

    /** Saves a fill-up, then recomputes derived values for the vehicle's whole history (an edit can change later fills). */
    suspend fun saveRefuel(event: RefuelEvent): RefuelEvent {
        val now = clock()
        val existing = db.refuels().get(event.id)
        val toSave = event.copy(createdAt = existing?.createdAt ?: now, updatedAt = now)
        db.refuels().upsert(toSave.toEntity())
        existing?.vehicleId?.takeIf { it != event.vehicleId }?.let { recompute(it) }
        recompute(event.vehicleId)
        return toSave
    }

    suspend fun deleteRefuel(id: String) {
        val e = db.refuels().get(id) ?: return
        db.refuels().delete(id)
        recompute(e.vehicleId)
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

    /** Deletes every vehicle and fill-up, and (unless [keepSettings]) all settings. */
    suspend fun deleteAll(keepSettings: Boolean = false) {
        db.withTransaction {
            db.vehicles().deleteAll()
            if (!keepSettings) db.settings().deleteAll()
        }
    }

    /** Adds vehicles and fill-ups from a restore. Existing ids are kept untouched (merge). */
    suspend fun importAll(vehicles: List<Vehicle>, events: List<RefuelEvent>) {
        db.withTransaction {
            db.vehicles().insertIgnore(vehicles.map { it.toEntity() })
            val ids = db.vehicles().all().map { it.id }.toSet()
            db.refuels().insertIgnore(events.filter { it.vehicleId in ids }.map { it.toEntity() })
        }
        recomputeAll()
    }
}
