package app.tel.data

import app.tel.data.db.SettingDao
import app.tel.data.db.SettingEntity
import app.tel.domain.model.FuelType
import app.tel.domain.model.ThemeMode
import app.tel.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How eagerly OCR values are flagged "Needs review". */
enum class OcrSensitivity(val label: String, val reviewThreshold: Float) {
    STRICT("Strict – flag more values", 0.9f),
    NORMAL("Normal", 0.75f),
    RELAXED("Relaxed – flag fewer values", 0.6f),
}

data class AppSettings(
    val onboarded: Boolean = false,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val defaultCurrency: String = "INR",
    val defaultFuelType: FuelType = FuelType.PETROL,
    /** Vehicle id, or null for "All vehicles". */
    val selectedVehicleId: String? = null,
    val ocrSensitivity: OcrSensitivity = OcrSensitivity.NORMAL,
)

class SettingsRepository(private val dao: SettingDao) {
    private object K {
        const val ONBOARDED = "onboarded"
        const val UNITS = "unit_system"
        const val THEME = "theme"
        const val CURRENCY = "default_currency"
        const val FUEL = "default_fuel_type"
        const val VEHICLE = "selected_vehicle"
        const val OCR = "ocr_sensitivity"
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
        ocrSensitivity = enumOr(m[K.OCR], OcrSensitivity.NORMAL),
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
    suspend fun setOcrSensitivity(s: OcrSensitivity) = put(K.OCR, s.name)
}
