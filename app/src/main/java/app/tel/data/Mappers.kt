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
    id = id, vehicleId = vehicleId, timestamp = timestamp, odometerKm = odometer,
    fuelAddedL = fuelAdded, pricePerL = pricePerUnit, totalCost = totalCost,
    currencyCode = currency, filledToFull = filledToFull, missedPrevious = missedPrevious, notes = notes,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun RefuelEvent.toEntity(metrics: RefuelMetrics? = null) = RefuelEntity(
    id = id, vehicleId = vehicleId, timestamp = timestamp, odometer = odometerKm,
    fuelAdded = fuelAddedL, fuelUnit = "L", pricePerUnit = pricePerL, totalCost = totalCost,
    currency = currencyCode, filledToFull = filledToFull, missedPrevious = missedPrevious,
    distanceSincePrevious = metrics?.distanceSincePreviousKm,
    fuelConsumed = metrics?.spanFuelL,
    mileage = metrics?.kmPerL,
    costPerDistance = metrics?.costPerKm,
    mileageStatus = metrics?.status?.name,
    notes = notes, createdAt = createdAt, updatedAt = updatedAt,
)
