package app.tel.data

import app.tel.data.db.RefuelEntity
import app.tel.data.db.VehicleEntity
import app.tel.domain.model.FuelType
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.domain.model.Vehicle

fun VehicleEntity.toDomain() = Vehicle(
    id = id, name = name, make = make, model = model, year = year,
    fuelType = runCatching { FuelType.valueOf(fuelType) }.getOrDefault(FuelType.OTHER),
    tankCapacityL = tankCapacity, currencyCode = currencyCode, registration = registration,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun Vehicle.toEntity() = VehicleEntity(
    id = id, name = name, make = make, model = model, year = year, fuelType = fuelType.name,
    tankCapacity = tankCapacityL, currencyCode = currencyCode, registration = registration,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun RefuelEntity.toDomain() = RefuelEvent(
    id = id, vehicleId = vehicleId, timestamp = timestamp,
    odometerBeforeKm = odometerBefore, odometerAfterKm = odometerAfter,
    fuelLevelBeforePct = fuelLevelBefore, fuelLevelAfterPct = fuelLevelAfter,
    fuelAddedL = fuelAdded, pricePerL = pricePerUnit, totalCost = totalCost,
    currencyCode = currency, filledToFull = filledToFull, notes = notes,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun RefuelEvent.toEntity(metrics: RefuelMetrics? = null) = RefuelEntity(
    id = id, vehicleId = vehicleId, timestamp = timestamp,
    odometerBefore = odometerBeforeKm, odometerAfter = odometerAfterKm,
    fuelLevelBefore = fuelLevelBeforePct, fuelLevelAfter = fuelLevelAfterPct,
    fuelAdded = fuelAddedL, fuelUnit = "L", pricePerUnit = pricePerL, totalCost = totalCost,
    currency = currencyCode, filledToFull = filledToFull,
    distanceSincePrevious = metrics?.distanceKm,
    fuelConsumedEstimate = metrics?.fuelConsumedL,
    mileage = metrics?.kmPerL,
    costPerDistance = metrics?.costPerKm,
    calculationQuality = metrics?.quality?.name,
    notes = notes, createdAt = createdAt, updatedAt = updatedAt,
)
