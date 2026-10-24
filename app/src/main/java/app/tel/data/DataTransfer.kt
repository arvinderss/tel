package app.tel.data

import app.tel.domain.csv.TelCsv
import app.tel.domain.model.FuelType
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.math.BigDecimal
import java.time.ZoneId
import java.util.zip.ZipInputStream

enum class RestoreMode { REPLACE, MERGE }

class TransferException(message: String) : Exception(message)

/** Data read from a file, ready to show the user before restoring. */
data class Restorable(
    val vehicles: List<Vehicle>,
    val events: List<RefuelEvent>,
    val skipped: List<String>,
    /** "CSV file" or "backup from an earlier version". */
    val kind: String,
)

/**
 * Exports and restores. Everything goes through files the user picks in the system dialog;
 * nothing is uploaded.
 *
 * - CSV ([TelCsv]): the main format, readable in any spreadsheet and fully restorable.
 * - JSON: a structured dump of the same data.
 * - .zip backups from versions 1.0.x are still restored (their photos are ignored).
 */
class DataTransfer(
    private val repository: FuelRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun csvText(): String {
        val vehicles = repository.vehicles.first()
        val events = repository.events.first()
        return "﻿" + TelCsv.encode(vehicles, events, repository.computeAll(events), zone())
    }

    suspend fun exportCsv(out: OutputStream) = withContext(Dispatchers.IO) {
        out.write(csvText().toByteArray(Charsets.UTF_8)); out.flush()
    }

    suspend fun exportJson(out: OutputStream) = withContext(Dispatchers.IO) {
        val vehicles = repository.vehicles.first()
        val events = repository.events.first()
        val o = JSONObject().put("app", "Tel").put("format", 3)
        o.put("vehicles", JSONArray(vehicles.map { v ->
            JSONObject().put("id", v.id).put("name", v.name).put("make", v.make).put("model", v.model)
                .put("year", v.year ?: JSONObject.NULL).put("fuel_type", v.fuelType.name).put("currency_code", v.currencyCode)
                .put("registration", v.registration).put("created_at", v.createdAt).put("updated_at", v.updatedAt)
        }))
        o.put("fill_ups", JSONArray(events.map { e ->
            JSONObject().put("id", e.id).put("vehicle_id", e.vehicleId).put("timestamp", e.timestamp)
                .put("odometer_km", e.odometerKm.j()).put("amount", e.totalCost.j()).put("currency", e.currencyCode)
                .put("rate_per_litre", e.pricePerL.j()).put("volume_litres", e.fuelAddedL.j())
                .put("tank_full", e.filledToFull).put("missed_previous_fill", e.missedPrevious).put("notes", e.notes)
                .put("created_at", e.createdAt).put("updated_at", e.updatedAt)
        }))
        out.write(o.toString(2).toByteArray()); out.flush()
    }

    /** Reads a Tel CSV, or a .zip backup from an earlier version. */
    fun read(bytes: ByteArray): Restorable {
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return readLegacyZip(bytes)
        val text = bytes.toString(Charsets.UTF_8)
        val d = try { TelCsv.decode(text, zone()) } catch (e: TelCsv.FormatException) { throw TransferException(e.message ?: "Not a Tel file.") }
        if (d.vehicles.isEmpty() && d.events.isEmpty()) throw TransferException("The file has no fill-ups.")
        return Restorable(d.vehicles, d.events, d.skipped, "CSV file")
    }

    /** REPLACE deletes current vehicles and fill-ups first (settings are kept); MERGE adds only new records. */
    suspend fun restore(r: Restorable, mode: RestoreMode) {
        if (mode == RestoreMode.REPLACE) repository.deleteAll(keepSettings = true)
        repository.importAll(r.vehicles, r.events)
    }

    // ------------------------------------------------------------------ legacy .zip (1.0.x)

    private fun readLegacyZip(bytes: ByteArray): Restorable {
        var data: JSONObject? = null
        runCatching {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "data.json") data = JSONObject(zip.readBytes().decodeToString())
                }
            }
        }.onFailure { throw TransferException("This file is damaged or isn't a Tel backup.") }
        val d = data ?: throw TransferException("This zip isn't a Tel backup.")
        val format = d.optInt("format", 1)
        val vehicles = d.optJSONArray("vehicles").objects().map { v ->
            Vehicle(
                id = v.getString("id"), name = v.optString("name", "My Car"), make = v.optString("make"), model = v.optString("model"),
                year = if (v.isNull("year")) null else v.optInt("year"),
                fuelType = runCatching { FuelType.valueOf(v.optString("fuel_type")) }.getOrDefault(FuelType.OTHER),
                tankCapacityL = v.dec("tank_capacity"), currencyCode = v.optString("currency_code", "INR"),
                registration = v.optString("registration"), createdAt = v.optLong("created_at"), updatedAt = v.optLong("updated_at"),
            )
        }
        val events = d.optJSONArray("refueling_events").objects().map { e ->
            RefuelEvent(
                id = e.getString("id"), vehicleId = e.getString("vehicle_id"), timestamp = e.getLong("timestamp"),
                // Format 1 had before/after readings; use "before", falling back to "after".
                odometerKm = e.dec("odometer") ?: e.dec("odometer_before") ?: e.dec("odometer_after"),
                fuelAddedL = e.dec("fuel_added"), pricePerL = e.dec("price_per_unit"), totalCost = e.dec("total_cost"),
                currencyCode = e.optString("currency", "INR"),
                // Format 1 defaulted the full-tank switch to off; treat those fills as full (see MIGRATION_1_2).
                filledToFull = if (format < 2) true else e.optBoolean("filled_to_full", true),
                missedPrevious = e.optBoolean("missed_previous", false),
                notes = e.optString("notes"), createdAt = e.optLong("created_at"), updatedAt = e.optLong("updated_at"),
            )
        }
        return Restorable(vehicles, events, emptyList(), "backup from an earlier version")
    }

    private fun BigDecimal?.j(): Any = this?.toPlainString() ?: JSONObject.NULL
    private fun JSONObject.dec(key: String): BigDecimal? =
        if (!has(key) || isNull(key)) null else runCatching { BigDecimal(get(key).toString()) }.getOrNull()
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
