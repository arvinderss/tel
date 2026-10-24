package app.tel.domain.csv

import app.tel.domain.format.CurrencyInfo
import app.tel.domain.format.Input
import app.tel.domain.model.FuelType
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.domain.model.Vehicle
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Tel's plain-text data file: ONE CSV holding all vehicles and fill-ups, so the app only ever
 * needs access to a single file.
 *
 * - One row per fill-up, with its vehicle's details repeated on the row.
 * - A vehicle without fill-ups gets a row of its own with the fill-up columns empty.
 * - Raw stored units (km, litres, price per litre) so the file never changes when the display
 *   units do. Dates are local time with the UTC offset, e.g. 2026-10-25 08:41:00+05:30.
 * - The "calculated" columns are for reading only and are ignored when restoring.
 */
object TelCsv {
    val COLUMNS = listOf(
        "date", "vehicle", "odometer_km", "amount", "currency", "rate_per_litre", "volume_litres",
        "tank_full", "missed_previous_fill", "notes",
        "distance_since_last_km", "mileage_km_per_litre", "cost_per_km", "mileage_status",
        "vehicle_fuel_type", "vehicle_make", "vehicle_model", "vehicle_year", "vehicle_registration",
        "vehicle_id", "fill_up_id", "created_at", "updated_at",
    )

    private val DATE_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX")

    fun encode(vehicles: List<Vehicle>, events: List<RefuelEvent>, metrics: Map<String, RefuelMetrics>, zone: ZoneId): String {
        val byId = vehicles.associateBy { it.id }
        val sb = StringBuilder()
        sb.append(Csv.line(COLUMNS)).append("\r\n")
        val sorted = events.sortedWith(compareBy<RefuelEvent>({ byId[it.vehicleId]?.name ?: "" }, { it.timestamp }, { it.createdAt }))
        for (e in sorted) {
            val v = byId[e.vehicleId] ?: continue
            val m = metrics[e.id]
            sb.append(Csv.line(listOf(
                DATE_OUT.format(Instant.ofEpochMilli(e.timestamp).atZone(zone)),
                v.name,
                e.odometerKm.plain(), e.totalCost.plain(), e.currencyCode, e.pricePerL.plain(), e.fuelAddedL.plain(),
                yesNo(e.filledToFull), yesNo(e.missedPrevious), e.notes,
                m?.distanceSincePreviousKm?.takeIf { it.signum() > 0 }.plain(),
                m?.kmPerL?.setScale(2, RoundingMode.HALF_UP).plain(),
                m?.costPerKm?.setScale(2, RoundingMode.HALF_UP).plain(),
                m?.status?.label ?: "",
            ) + vehicleFields(v) + listOf(e.id, e.createdAt.toString(), e.updatedAt.toString()))).append("\r\n")
        }
        val withEvents = events.map { it.vehicleId }.toSet()
        for (v in vehicles.filter { it.id !in withEvents }.sortedBy { it.createdAt }) {
            sb.append(Csv.line(listOf("", v.name, "", "", v.currencyCode, "", "", "", "", "", "", "", "", "") +
                vehicleFields(v) + listOf("", v.createdAt.toString(), v.updatedAt.toString()))).append("\r\n")
        }
        return sb.toString()
    }

    private fun vehicleFields(v: Vehicle) = listOf(
        v.fuelType.name, v.make, v.model, v.year?.toString() ?: "", v.registration, v.id,
    )

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"
    private fun BigDecimal?.plain() = this?.stripTrailingZeros()?.let { if (it.scale() < 0) it.setScale(0) else it }?.toPlainString() ?: ""

    // ------------------------------------------------------------------ decoding

    data class Decoded(
        val vehicles: List<Vehicle>,
        val events: List<RefuelEvent>,
        /** Human-readable reasons for rows that were skipped. */
        val skipped: List<String>,
    )

    class FormatException(message: String) : Exception(message)

    /**
     * Reads a Tel CSV. Tolerates edits made in a spreadsheet: reordered columns, rows added
     * without ids, numbers like "4,086.10", dates without seconds or offset.
     */
    fun decode(text: String, zone: ZoneId, now: Long = System.currentTimeMillis()): Decoded {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) throw FormatException("The file is empty.")
        val header = rows.first().map { it.trim().lowercase() }
        val col = header.withIndex().associate { it.value to it.index }
        val required = listOf("date", "vehicle", "odometer_km", "amount", "volume_litres")
        val missing = required.filter { it !in col }
        if (missing.isNotEmpty()) throw FormatException("This doesn't look like a Tel CSV file (missing: ${missing.joinToString()}).")

        val vehicles = LinkedHashMap<String, Vehicle>()
        val nameToId = HashMap<String, String>()
        val events = mutableListOf<RefuelEvent>()
        val skipped = mutableListOf<String>()

        for ((n, r) in rows.drop(1).withIndex()) {
            val lineNo = n + 2
            fun f(name: String): String = col[name]?.let { r.getOrNull(it) }?.trim().orEmpty()
            fun num(name: String): BigDecimal? = f(name).takeIf { it.isNotEmpty() }?.let { Input.parse(it.replace(" ", "")) }

            val vehicleName = f("vehicle").ifEmpty { "My Car" }
            val vid = f("vehicle_id").ifEmpty { nameToId[vehicleName.lowercase()] ?: UUID.randomUUID().toString() }
            nameToId.putIfAbsent(vehicleName.lowercase(), vid)
            val currency = f("currency").uppercase().takeIf { CurrencyInfo.isValid(it) } ?: vehicles[vid]?.currencyCode ?: "INR"
            if (vid !in vehicles) {
                vehicles[vid] = Vehicle(
                    id = vid, name = vehicleName,
                    make = f("vehicle_make"), model = f("vehicle_model"), year = f("vehicle_year").toIntOrNull(),
                    fuelType = runCatching { FuelType.valueOf(f("vehicle_fuel_type").uppercase()) }.getOrDefault(FuelType.PETROL),
                    currencyCode = currency, registration = f("vehicle_registration"),
                    createdAt = f("created_at").toLongOrNull() ?: now, updatedAt = now,
                )
            }

            val dateText = f("date")
            val isVehicleOnly = dateText.isEmpty() && f("fill_up_id").isEmpty() && f("odometer_km").isEmpty() &&
                f("amount").isEmpty() && f("volume_litres").isEmpty()
            if (isVehicleOnly) continue

            val ts = parseDate(dateText, zone)
            if (ts == null) { skipped += "Row $lineNo: date \"$dateText\" not understood."; continue }
            val bad = listOf("odometer_km", "amount", "rate_per_litre", "volume_litres").filter { f(it).isNotEmpty() && num(it) == null }
            if (bad.isNotEmpty()) { skipped += "Row $lineNo: ${bad.joinToString()} not a number."; continue }

            val ev = RefuelEvent(
                id = f("fill_up_id").ifEmpty { UUID.randomUUID().toString() },
                vehicleId = vid,
                timestamp = ts,
                odometerKm = num("odometer_km"),
                fuelAddedL = num("volume_litres"),
                pricePerL = num("rate_per_litre"),
                totalCost = num("amount"),
                currencyCode = currency,
                filledToFull = bool(f("tank_full")) ?: true,
                missedPrevious = bool(f("missed_previous_fill")) ?: false,
                notes = f("notes"),
                createdAt = f("created_at").toLongOrNull() ?: now,
                updatedAt = f("updated_at").toLongOrNull() ?: now,
            )
            if (ev.isEmpty) { skipped += "Row $lineNo: no values."; continue }
            events += ev
        }
        // Duplicate ids (e.g. a copied row in a spreadsheet): keep the first.
        val unique = events.distinctBy { it.id }
        if (unique.size < events.size) skipped += "${events.size - unique.size} duplicate row(s) ignored."
        return Decoded(vehicles.values.toList(), unique, skipped)
    }

    private fun bool(s: String): Boolean? = when (s.trim().lowercase()) {
        "yes", "y", "true", "1" -> true
        "no", "n", "false", "0" -> false
        else -> null
    }

    private val localFormats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "dd/MM/yyyy HH:mm", "dd-MM-yyyy HH:mm")
        .map { DateTimeFormatter.ofPattern(it) }
    private val dateOnly = listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy").map { DateTimeFormatter.ofPattern(it) }

    fun parseDate(s: String, zone: ZoneId): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(t, DATE_OUT).toInstant().toEpochMilli() }
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(t).toEpochMilli() }
        for (f in localFormats) runCatching { return LocalDateTime.parse(t, f).atZone(zone).toInstant().toEpochMilli() }
        for (f in dateOnly) runCatching { return LocalDate.parse(t, f).atTime(12, 0).atZone(zone).toInstant().toEpochMilli() }
        return null
    }
}
