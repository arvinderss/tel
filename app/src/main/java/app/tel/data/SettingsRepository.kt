package app.tel.data

import app.tel.data.db.SettingDao
import app.tel.data.db.SettingEntity
import app.tel.domain.model.FuelType
import app.tel.domain.model.ThemeMode
import app.tel.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val onboarded: Boolean = false,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val defaultCurrency: String = "INR",
    val defaultFuelType: FuelType = FuelType.PETROL,
    /** Vehicle id, or null for "All vehicles". */
    val selectedVehicleId: String? = null,
    val csv: CsvCopySettings = CsvCopySettings(),
    /** The "photograph the meters and enter later" tip on the fill-up form was dismissed. */
    val photoTipHidden: Boolean = false,
)

/** The optional CSV copy kept outside the app (one file the user picked). */
data class CsvCopySettings(
    /** content:// URI of the file, with a persisted read/write grant. Null = not set up. */
    val uri: String? = null,
    val fileName: String? = null,
    val lastWritten: Long? = null,
    /** Set when the last update failed (file moved, deleted, or access revoked). */
    val error: String? = null,
    /** The user chose "Don't remind me again". */
    val neverRemind: Boolean = false,
    /** "Not now": hide the reminder until this time. */
    val snoozedUntil: Long = 0L,
) {
    val enabled: Boolean get() = uri != null
    fun shouldRemind(now: Long): Boolean = uri == null && !neverRemind && now >= snoozedUntil
}

class SettingsRepository(private val dao: SettingDao) {
    private object K {
        const val ONBOARDED = "onboarded"
        const val UNITS = "unit_system"
        const val THEME = "theme"
        const val CURRENCY = "default_currency"
        const val FUEL = "default_fuel_type"
        const val VEHICLE = "selected_vehicle"
        const val CSV_URI = "csv_uri"
        const val CSV_NAME = "csv_name"
        const val CSV_WRITTEN = "csv_last_written"
        const val CSV_ERROR = "csv_error"
        const val CSV_NEVER = "csv_never_remind"
        const val CSV_SNOOZE = "csv_snoozed_until"
        const val PHOTO_TIP = "photo_tip_hidden"
    }

    val settings: Flow<AppSettings> = dao.observeAll().map { rows -> parse(rows.associate { it.key to it.value }) }

    suspend fun current(): AppSettings = parse(dao.all().associate { it.key to it.value })

    private fun parse(m: Map<String, String>) = AppSettings(
        onboarded = m[K.ONBOARDED] == "true",
        unitSystem = enumOr(m[K.UNITS], UnitSystem.METRIC),
        theme = enumOr(m[K.THEME], ThemeMode.SYSTEM),
        defaultCurrency = m[K.CURRENCY] ?: "INR",
        defaultFuelType = enumOr(m[K.FUEL], FuelType.PETROL),
        selectedVehicleId = m[K.VEHICLE]?.takeIf { it.isNotEmpty() },
        csv = CsvCopySettings(
            uri = m[K.CSV_URI]?.takeIf { it.isNotEmpty() },
            fileName = m[K.CSV_NAME]?.takeIf { it.isNotEmpty() },
            lastWritten = m[K.CSV_WRITTEN]?.toLongOrNull(),
            error = m[K.CSV_ERROR]?.takeIf { it.isNotEmpty() },
            neverRemind = m[K.CSV_NEVER] == "true",
            snoozedUntil = m[K.CSV_SNOOZE]?.toLongOrNull() ?: 0L,
        ),
        photoTipHidden = m[K.PHOTO_TIP] == "true",
    )

    private inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E =
        v?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: d

    private suspend fun put(k: String, v: String) = dao.put(SettingEntity(k, v))

    suspend fun setOnboarded() = put(K.ONBOARDED, "true")
    suspend fun setUnitSystem(u: UnitSystem) = put(K.UNITS, u.name)
    suspend fun setTheme(t: ThemeMode) = put(K.THEME, t.name)
    suspend fun setDefaultCurrency(c: String) = put(K.CURRENCY, c)
    suspend fun setDefaultFuelType(f: FuelType) = put(K.FUEL, f.name)
    suspend fun setSelectedVehicle(id: String?) = put(K.VEHICLE, id ?: "")
    suspend fun hidePhotoTip() = put(K.PHOTO_TIP, "true")

    suspend fun setCsvFile(uri: String?, name: String?) {
        dao.putAll(listOf(SettingEntity(K.CSV_URI, uri ?: ""), SettingEntity(K.CSV_NAME, name ?: ""), SettingEntity(K.CSV_ERROR, "")))
    }
    suspend fun setCsvWritten(at: Long) = dao.putAll(listOf(SettingEntity(K.CSV_WRITTEN, at.toString()), SettingEntity(K.CSV_ERROR, "")))
    suspend fun setCsvError(message: String) = put(K.CSV_ERROR, message)
    suspend fun neverRemindCsv() = put(K.CSV_NEVER, "true")
    suspend fun snoozeCsvReminder(until: Long) = put(K.CSV_SNOOZE, until.toString())
}
