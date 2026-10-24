package app.tel.ui.refuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tel.AppContainer
import app.tel.domain.calc.PumpField
import app.tel.domain.calc.PumpTriad
import app.tel.domain.format.Formatter
import app.tel.domain.format.Input
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.domain.model.UnitSystem
import app.tel.domain.model.Vehicle
import app.tel.domain.units.Units
import app.tel.domain.validation.Fields
import app.tel.domain.validation.Issue
import app.tel.domain.validation.Severity
import app.tel.domain.validation.Validator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID

enum class Step { LOADING, FORM, SAVING, SAVED }

/** The fill-up shown next to the odometer field, to catch typos. */
data class OdometerReference(val km: BigDecimal, val timestamp: Long)

data class RefuelUi(
    val step: Step = Step.LOADING,
    val isEdit: Boolean = false,
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: String? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val timestamp: Long = System.currentTimeMillis(),
    val filledToFull: Boolean = true,
    val pump: PumpTriad = PumpTriad(),
    /** Set when the rate was prefilled from an earlier fill-up. */
    val rateFrom: Long? = null,
    val odometer: String = "",
    val previousOdometer: OdometerReference? = null,
    val showPhotoTip: Boolean = false,
    val notes: String = "",
    val missedPrevious: Boolean = false,
    val issues: List<Issue> = emptyList(),
    val preview: RefuelMetrics? = null,
    val askConfirmWarnings: Boolean = false,
    val message: String? = null,
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == vehicleId }
    val errors get() = issues.filter { it.severity == Severity.ERROR }
    val warnings get() = issues.filter { it.severity == Severity.WARNING }
    fun issueFor(field: String) = issues.firstOrNull { it.field == field }
}

fun PumpField.key() = when (this) {
    PumpField.AMOUNT -> Fields.TOTAL
    PumpField.RATE -> Fields.PRICE
    PumpField.VOLUME -> Fields.FUEL_ADDED
}

class RefuelViewModel(private val c: AppContainer, private val existingId: String?) : ViewModel() {
    private val eventId: String = existingId ?: UUID.randomUUID().toString()
    private var createdAt = 0L

    private val _ui = MutableStateFlow(RefuelUi())
    val ui: StateFlow<RefuelUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private fun emptyTriad(units: UnitSystem): PumpTriad {
        val fine = if (units == UnitSystem.US) 3 else 2
        return PumpTriad(amountScale = 2, rateScale = fine, volumeScale = fine)
    }

    private suspend fun load() {
        val settings = c.settings.current()
        val vehicles = c.repository.vehicles.first()
        val units = settings.unitSystem
        val e = existingId?.let { c.repository.event(it) }
        if (e == null) {
            val vid = settings.selectedVehicleId?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.firstOrNull()?.id
            _ui.value = RefuelUi(
                step = Step.FORM, vehicles = vehicles, vehicleId = vid, unitSystem = units, pump = emptyTriad(units),
                showPhotoTip = !settings.photoTipHidden,
                message = if (existingId != null) "That fill-up no longer exists." else null,
            )
            prefillRate()
        } else {
            createdAt = e.createdAt
            val fine = if (units == UnitSystem.US) 3 else 2
            val pump = PumpTriad.fromStored(
                amount = e.totalCost,
                rate = e.pricePerL?.let { Units.pricePerLToDisplay(it, units) },
                volume = e.fuelAddedL?.let { Units.litresToDisplay(it, units) },
                rateScale = fine, volumeScale = fine,
            )
            _ui.value = RefuelUi(
                step = Step.FORM, isEdit = true, vehicles = vehicles, vehicleId = e.vehicleId, unitSystem = units,
                timestamp = e.timestamp, filledToFull = e.filledToFull, pump = pump,
                odometer = Input.format(e.odometerKm?.let { Units.kmToDisplay(it, units) }, 1),
                notes = e.notes, missedPrevious = e.missedPrevious,
            )
        }
        refresh()
    }

    /** Suggests the rate from the vehicle's latest earlier fill-up. Only for new entries. */
    private suspend fun prefillRate() {
        val s = _ui.value
        if (s.isEdit) return
        val vid = s.vehicleId ?: return
        val last = c.engine.chronological(c.repository.eventsFor(vid))
            .lastOrNull { it.pricePerL != null && it.timestamp <= s.timestamp && it.id != eventId }
        val current = s.pump[PumpField.RATE].source
        if (current == app.tel.domain.calc.EntrySource.TYPED) return
        val cleared = if (current == app.tel.domain.calc.EntrySource.PREFILLED) s.pump.edit(PumpField.RATE, "") else s.pump
        val pump = last?.pricePerL?.let { cleared.prefill(PumpField.RATE, Units.pricePerLToDisplay(it, s.unitSystem)) } ?: cleared
        _ui.update { it.copy(pump = pump, rateFrom = last?.timestamp) }
    }

    // ------------------------------------------------------------------ inputs

    fun selectVehicle(id: String) = launchRefresh { _ui.update { it.copy(vehicleId = id) }; prefillRate() }
    fun setTimestamp(ms: Long) = launchRefresh { _ui.update { it.copy(timestamp = ms) } }
    fun setFilledToFull(v: Boolean) = launchRefresh { _ui.update { it.copy(filledToFull = v) } }
    fun setMissedPrevious(v: Boolean) = launchRefresh { _ui.update { it.copy(missedPrevious = v) } }
    fun setNotes(v: String) = _ui.update { it.copy(notes = v) }
    fun setOdometer(v: String) = launchRefresh { _ui.update { it.copy(odometer = v) } }
    fun editPump(f: PumpField, text: String) = launchRefresh {
        _ui.update { it.copy(pump = it.pump.edit(f, text), rateFrom = if (f == PumpField.RATE) null else it.rateFrom) }
    }
    fun dismissMessage() = _ui.update { it.copy(message = null) }
    fun dismissWarnings() = _ui.update { it.copy(askConfirmWarnings = false) }

    private fun launchRefresh(block: suspend () -> Unit) {
        viewModelScope.launch { block(); refresh() }
    }

    fun hidePhotoTip() {
        _ui.update { it.copy(showPhotoTip = false) }
        viewModelScope.launch { c.settings.hidePhotoTip() }
    }

    // ------------------------------------------------------------------ validation & preview

    private fun buildEvent(s: RefuelUi): RefuelEvent? {
        val vehicle = s.vehicle ?: return null
        val u = s.unitSystem
        return RefuelEvent(
            id = eventId,
            vehicleId = vehicle.id,
            timestamp = s.timestamp,
            odometerKm = Input.parse(s.odometer)?.let { Units.displayToKm(it, u) },
            fuelAddedL = s.pump[PumpField.VOLUME].value?.let { Units.displayToLitres(it, u) },
            pricePerL = s.pump[PumpField.RATE].value?.let { Units.displayPriceToPerL(it, u) },
            totalCost = s.pump[PumpField.AMOUNT].value,
            currencyCode = vehicle.currencyCode,
            filledToFull = s.filledToFull,
            missedPrevious = s.missedPrevious,
            notes = s.notes.trim(),
            createdAt = createdAt,
        )
    }

    private suspend fun refresh() {
        val s = _ui.value
        val ev = buildEvent(s) ?: run { _ui.update { it.copy(issues = emptyList(), preview = null) }; return }
        val history = c.repository.eventsFor(ev.vehicleId).filter { it.id != ev.id }
        val ordered = c.engine.chronological(history + ev)
        val idx = ordered.indexOfFirst { it.id == ev.id }
        val prev = ordered.take(idx).lastOrNull { it.odometerKm != null }
        val next = ordered.drop(idx + 1).firstOrNull { it.odometerKm != null }

        val fmt = Formatter(s.unitSystem)
        val issues = Validator.validate(ev, prev, next, formatKm = { fmt.distance(it) }).toMutableList()
        if (Input.isInvalid(s.odometer)) issues.add(0, Issue(Fields.ODOMETER, Severity.ERROR, "Enter a number."))
        for (f in PumpField.entries) {
            if (Input.isInvalid(s.pump[f].text)) issues.add(0, Issue(f.key(), Severity.ERROR, "Enter a number."))
        }
        val preview = c.engine.compute(ordered)[ev.id]
        _ui.update {
            it.copy(
                issues = issues, preview = preview,
                previousOdometer = prev?.let { p -> OdometerReference(p.odometerKm!!, p.timestamp) },
            )
        }
    }

    // ------------------------------------------------------------------ save

    fun save(confirmedWarnings: Boolean = false) {
        viewModelScope.launch {
            refresh()
            val s = _ui.value
            val ev = buildEvent(s)
            if (ev == null) { _ui.update { it.copy(message = "Add a vehicle first.") }; return@launch }
            if (s.errors.isNotEmpty()) { _ui.update { it.copy(message = s.errors.first().message) }; return@launch }
            if (s.warnings.isNotEmpty() && !confirmedWarnings) { _ui.update { it.copy(askConfirmWarnings = true) }; return@launch }
            _ui.update { it.copy(step = Step.SAVING, askConfirmWarnings = false) }
            try {
                c.repository.saveRefuel(ev)
                _ui.update { it.copy(step = Step.SAVED) }
            } catch (t: Throwable) {
                _ui.update { it.copy(step = Step.FORM, message = "Couldn't save. Please try again.") }
            }
        }
    }
}
