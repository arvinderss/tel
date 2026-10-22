package app.tel.ui.refuel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tel.AppContainer
import app.tel.data.AppSettings
import app.tel.data.OcrResultInput
import app.tel.data.PhotoChange
import app.tel.domain.format.Input
import app.tel.domain.model.ImageType
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.domain.model.UnitSystem
import app.tel.domain.model.ValueSource
import app.tel.domain.model.Vehicle
import app.tel.domain.ocr.Candidate
import app.tel.domain.ocr.ImageReading
import app.tel.domain.ocr.OcrField
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
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.UUID

enum class Step { LOADING, PHOTOS, PROCESSING, REVIEW, SAVING, SAVED }

/** Form fields, in display units. [key] matches [Fields] so validation messages land on the right input. */
enum class FormField(val key: String, val label: String) {
    ODO_BEFORE(Fields.ODO_BEFORE, "Odometer"),
    LEVEL_BEFORE(Fields.LEVEL_BEFORE, "Fuel level"),
    ODO_AFTER(Fields.ODO_AFTER, "Odometer"),
    LEVEL_AFTER(Fields.LEVEL_AFTER, "Fuel level"),
    FUEL_ADDED(Fields.FUEL_ADDED, "Fuel added"),
    PRICE(Fields.PRICE, "Price"),
    TOTAL(Fields.TOTAL, "Total paid"),
}

data class FieldState(
    val text: String = "",
    val source: ValueSource = ValueSource.EMPTY,
    val confidence: Float? = null,
    val needsReview: Boolean = false,
    /** Other values the photo could mean; the user can tap one to use it. */
    val candidates: List<Candidate> = emptyList(),
)

data class PhotoSlot(
    val type: ImageType,
    val file: File? = null,
    val isNew: Boolean = false,
    val storedPath: String? = null,
    val reading: ImageReading? = null,
    /** True when [reading] was produced in this session and should be saved. */
    val freshReading: Boolean = false,
    val removed: Boolean = false,
) {
    val present: Boolean get() = file != null && !removed
}

data class RefuelUi(
    val step: Step = Step.LOADING,
    val isEdit: Boolean = false,
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: String? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val photos: Map<ImageType, PhotoSlot> = ImageType.entries.associateWith { PhotoSlot(it) },
    val fields: Map<FormField, FieldState> = FormField.entries.associateWith { FieldState() },
    val timestamp: Long = System.currentTimeMillis(),
    val filledToFull: Boolean = false,
    val notes: String = "",
    val issues: List<Issue> = emptyList(),
    val preview: RefuelMetrics? = null,
    val isFirstForVehicle: Boolean = true,
    val askConfirmWarnings: Boolean = false,
    val message: String? = null,
    val processingLabel: String = "",
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == vehicleId }
    fun field(f: FormField) = fields.getValue(f)
    val errors get() = issues.filter { it.severity == Severity.ERROR }
    val warnings get() = issues.filter { it.severity == Severity.WARNING }
    fun issueFor(f: FormField) = issues.firstOrNull { it.field == f.key }
}

class RefuelViewModel(
    private val c: AppContainer,
    private val existingId: String?,
    private val reprocess: Boolean,
) : ViewModel() {
    private val eventId: String = existingId ?: UUID.randomUUID().toString()
    private var createdAt = 0L
    private var settings = AppSettings()
    private val mc = MathContext.DECIMAL64

    private val _ui = MutableStateFlow(RefuelUi())
    val ui: StateFlow<RefuelUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        settings = c.settings.current()
        val vehicles = c.repository.vehicles.first()
        val units = settings.unitSystem
        if (existingId == null) {
            val vid = settings.selectedVehicleId?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.firstOrNull()?.id
            _ui.value = RefuelUi(step = Step.PHOTOS, vehicles = vehicles, vehicleId = vid, unitSystem = units)
            refresh()
            return
        }
        val e = c.repository.event(existingId) ?: run {
            _ui.value = RefuelUi(step = Step.PHOTOS, vehicles = vehicles, vehicleId = vehicles.firstOrNull()?.id, unitSystem = units, message = "That refuelling no longer exists.")
            return
        }
        createdAt = e.createdAt
        val stored = c.repository.imagesFor(existingId)
        val ocrRows = c.repository.observeOcr(existingId).first()
        val ocrValues = ocrRows.mapNotNull { r -> r.parsedValue?.let { r.fieldName to it } }

        fun fs(v: BigDecimal?, f: FormField, ocrField: String, scale: Int): FieldState {
            if (v == null) return FieldState()
            val text = Input.format(v, scale)
            val parsed = Input.parse(text)
            val fromOcr = parsed != null && ocrValues.any { (k, ov) -> k == ocrField && ov.compareTo(parsed) == 0 }
            return FieldState(text, if (fromOcr) ValueSource.OCR else ValueSource.USER)
        }
        val fields = mapOf(
            FormField.ODO_BEFORE to fs(e.odometerBeforeKm?.let { Units.kmToDisplay(it, units) }, FormField.ODO_BEFORE, "before_hud.odometer", 1),
            FormField.LEVEL_BEFORE to fs(e.fuelLevelBeforePct, FormField.LEVEL_BEFORE, "before_hud.fuel_level", 1),
            FormField.ODO_AFTER to fs(e.odometerAfterKm?.let { Units.kmToDisplay(it, units) }, FormField.ODO_AFTER, "after_hud.odometer", 1),
            FormField.LEVEL_AFTER to fs(e.fuelLevelAfterPct, FormField.LEVEL_AFTER, "after_hud.fuel_level", 1),
            FormField.FUEL_ADDED to fs(e.fuelAddedL?.let { Units.litresToDisplay(it, units) }, FormField.FUEL_ADDED, "pump_meter.fuel_quantity", 3),
            FormField.PRICE to fs(e.pricePerL?.let { Units.pricePerLToDisplay(it, units) }, FormField.PRICE, "pump_meter.price_per_unit", 3),
            FormField.TOTAL to fs(e.totalCost, FormField.TOTAL, "pump_meter.total_amount", 2),
        )
        val photos = ImageType.entries.associateWith { t ->
            stored.firstOrNull { it.type == t }?.let { PhotoSlot(t, it.file, isNew = false, storedPath = it.relativePath) } ?: PhotoSlot(t)
        }
        _ui.value = RefuelUi(
            step = Step.REVIEW, isEdit = true, vehicles = vehicles, vehicleId = e.vehicleId, unitSystem = units,
            photos = photos, fields = fields, timestamp = e.timestamp, filledToFull = e.filledToFull, notes = e.notes,
        )
        refresh()
        if (reprocess) processPhotos(force = true)
    }

    // ------------------------------------------------------------------ inputs

    fun selectVehicle(id: String) { _ui.update { it.copy(vehicleId = id) }; viewModelScope.launch { refresh() } }

    fun setTimestamp(ms: Long) { _ui.update { it.copy(timestamp = ms) }; viewModelScope.launch { refresh() } }

    fun setFilledToFull(v: Boolean) { _ui.update { it.copy(filledToFull = v) }; viewModelScope.launch { refresh() } }

    fun setNotes(v: String) = _ui.update { it.copy(notes = v) }

    fun edit(f: FormField, text: String) {
        _ui.update { s ->
            val fields = s.fields.toMutableMap()
            val old = fields.getValue(f)
            fields[f] = old.copy(text = text, source = if (text.isBlank()) ValueSource.EMPTY else ValueSource.USER, needsReview = false)
            s.copy(fields = recalc(fields))
        }
        viewModelScope.launch { refresh() }
    }

    fun useCandidate(f: FormField, c: Candidate) {
        _ui.update { s ->
            val fields = s.fields.toMutableMap()
            fields[f] = fields.getValue(f).copy(text = c.value.toPlainString(), source = ValueSource.USER, needsReview = false)
            s.copy(fields = recalc(fields))
        }
        viewModelScope.launch { refresh() }
    }

    fun skipToManual() = _ui.update { it.copy(step = Step.REVIEW) }

    fun backToPhotos() = _ui.update { it.copy(step = Step.PHOTOS) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    // ------------------------------------------------------------------ photos

    fun newCameraFile(): File = c.imageStore.newCameraFile()

    fun addPhoto(type: ImageType, uri: Uri) {
        viewModelScope.launch {
            try {
                val file = c.imageStore.importToDraft(uri, eventId, type)
                _ui.update { s ->
                    s.copy(photos = s.photos + (type to PhotoSlot(type, file, isNew = true, storedPath = s.photos[type]?.storedPath)))
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(message = "That image couldn't be opened. Try another photo.") }
            }
        }
    }

    fun removePhoto(type: ImageType) {
        _ui.update { s ->
            val old = s.photos.getValue(type)
            s.copy(photos = s.photos + (type to PhotoSlot(type, storedPath = old.storedPath, removed = old.storedPath != null)))
        }
    }

    /** Runs OCR on every photo that hasn't been read yet (or all, when [force]). */
    fun processPhotos(force: Boolean = false) {
        val slots = _ui.value.photos.values.filter { it.present && (force || it.reading == null) }
        if (slots.isEmpty()) { _ui.update { it.copy(step = Step.REVIEW) }; return }
        _ui.update { it.copy(step = Step.PROCESSING, processingLabel = "Processing images…") }
        viewModelScope.launch {
            for ((i, slot) in slots.withIndex()) {
                _ui.update { it.copy(processingLabel = "Reading ${slot.type.label.lowercase()} photo (${i + 1} of ${slots.size})…") }
                val reading = c.ocr.process(slot.type, slot.file!!)
                _ui.update { s -> s.copy(photos = s.photos + (slot.type to slot.copy(reading = reading, freshReading = true))) }
                applyReading(slot.type, reading)
            }
            _ui.update { it.copy(step = Step.REVIEW) }
            refresh()
        }
    }

    private fun applyReading(type: ImageType, r: ImageReading) {
        val map: List<Pair<OcrField, FormField>> = when (type) {
            ImageType.BEFORE_HUD -> listOf(OcrField.ODOMETER to FormField.ODO_BEFORE, OcrField.FUEL_LEVEL to FormField.LEVEL_BEFORE)
            ImageType.AFTER_HUD -> listOf(OcrField.ODOMETER to FormField.ODO_AFTER, OcrField.FUEL_LEVEL to FormField.LEVEL_AFTER)
            ImageType.PUMP_METER -> listOf(
                OcrField.FUEL_QUANTITY to FormField.FUEL_ADDED,
                OcrField.PRICE_PER_UNIT to FormField.PRICE,
                OcrField.TOTAL_AMOUNT to FormField.TOTAL,
            )
        }
        val threshold = settings.ocrSensitivity.reviewThreshold
        _ui.update { s ->
            val fields = s.fields.toMutableMap()
            for ((ocrField, formField) in map) {
                val res = r[ocrField] ?: continue
                val best = res.best ?: continue
                val old = fields.getValue(formField)
                if (old.source == ValueSource.USER && old.text.isNotBlank()) {
                    // Never overwrite something the user typed; offer the photo's value instead.
                    val same = Input.parse(old.text)?.compareTo(best.value) == 0
                    if (!same) fields[formField] = old.copy(candidates = (listOf(best) + res.alternatives).distinctBy { it.value })
                } else {
                    fields[formField] = FieldState(
                        text = best.value.toPlainString(),
                        source = ValueSource.OCR,
                        confidence = best.confidence,
                        needsReview = best.confidence < threshold,
                        candidates = res.alternatives,
                    )
                }
            }
            s.copy(fields = recalc(fields))
        }
    }

    /** Fills the one missing value of fuel × price = total, marked as calculated. */
    private fun recalc(fields: MutableMap<FormField, FieldState>): Map<FormField, FieldState> {
        fun given(f: FormField): BigDecimal? = fields.getValue(f).takeIf { it.source != ValueSource.CALCULATED }?.let { Input.parse(it.text) }
        val q = given(FormField.FUEL_ADDED); val p = given(FormField.PRICE); val t = given(FormField.TOTAL)
        // Clear stale calculated values first.
        for (f in listOf(FormField.FUEL_ADDED, FormField.PRICE, FormField.TOTAL)) {
            if (fields.getValue(f).source == ValueSource.CALCULATED) fields[f] = FieldState()
        }
        when {
            q != null && p != null && t == null ->
                fields[FormField.TOTAL] = FieldState(q.multiply(p, mc).setScale(2, RoundingMode.HALF_UP).toPlainString(), ValueSource.CALCULATED)
            q != null && t != null && p == null && q.signum() > 0 ->
                fields[FormField.PRICE] = FieldState(t.divide(q, 3, RoundingMode.HALF_UP).toPlainString(), ValueSource.CALCULATED)
            p != null && t != null && q == null && p.signum() > 0 ->
                fields[FormField.FUEL_ADDED] = FieldState(t.divide(p, 3, RoundingMode.HALF_UP).toPlainString(), ValueSource.CALCULATED)
        }
        return fields
    }

    // ------------------------------------------------------------------ validation & preview

    private fun buildEvent(s: RefuelUi): RefuelEvent? {
        val vehicle = s.vehicle ?: return null
        val u = s.unitSystem
        fun v(f: FormField) = Input.parse(s.field(f).text)
        return RefuelEvent(
            id = eventId,
            vehicleId = vehicle.id,
            timestamp = s.timestamp,
            odometerBeforeKm = v(FormField.ODO_BEFORE)?.let { Units.displayToKm(it, u) },
            odometerAfterKm = v(FormField.ODO_AFTER)?.let { Units.displayToKm(it, u) },
            fuelLevelBeforePct = v(FormField.LEVEL_BEFORE),
            fuelLevelAfterPct = v(FormField.LEVEL_AFTER),
            fuelAddedL = v(FormField.FUEL_ADDED)?.let { Units.displayToLitres(it, u) },
            pricePerL = v(FormField.PRICE)?.let { Units.displayPriceToPerL(it, u) },
            totalCost = v(FormField.TOTAL),
            currencyCode = vehicle.currencyCode,
            filledToFull = s.filledToFull,
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
        val prev = ordered.getOrNull(idx - 1)
        val next = ordered.getOrNull(idx + 1)

        val issues = Validator.validate(ev, s.vehicle, prev, next).toMutableList()
        // Text that isn't a number at all.
        for (f in FormField.entries) {
            if (Input.isInvalid(s.field(f).text)) issues.add(0, Issue(f.key, Severity.ERROR, "Enter a number."))
        }
        val preview = prev?.let { c.engine.computeOne(it, ev, s.vehicle?.tankCapacityL) }
        _ui.update { it.copy(issues = issues, preview = preview, isFirstForVehicle = prev == null) }
    }

    // ------------------------------------------------------------------ save

    fun save(confirmedWarnings: Boolean = false) {
        viewModelScope.launch {
            refresh()
            val s = _ui.value
            val ev = buildEvent(s)
            if (ev == null) { _ui.update { it.copy(message = "Add a vehicle first.") }; return@launch }
            if (s.errors.isNotEmpty()) { _ui.update { it.copy(message = "Please fix the highlighted values.") }; return@launch }
            if (s.warnings.isNotEmpty() && !confirmedWarnings) { _ui.update { it.copy(askConfirmWarnings = true) }; return@launch }
            _ui.update { it.copy(step = Step.SAVING, askConfirmWarnings = false) }
            try {
                val changes = s.photos.values.mapNotNull { slot -> photoChange(slot)?.let { slot.type to it } }.toMap()
                c.repository.saveRefuel(ev, changes)
                c.imageStore.clearDraft(eventId)
                _ui.update { it.copy(step = Step.SAVED) }
            } catch (t: Throwable) {
                _ui.update { it.copy(step = Step.REVIEW, message = "Couldn't save. Please try again.") }
            }
        }
    }

    fun dismissWarnings() = _ui.update { it.copy(askConfirmWarnings = false) }

    private fun photoChange(slot: PhotoSlot): PhotoChange? {
        val ocr = slot.reading?.let { r ->
            r.fields.values.mapNotNull { fr ->
                fr.best?.let { OcrResultInput("${slot.type.key}.${fr.field.key}", it.raw, it.value, it.confidence) }
            }
        }.orEmpty()
        return when {
            slot.removed -> PhotoChange.Remove
            slot.isNew && slot.file != null -> PhotoChange.New(slot.file, ocr)
            slot.freshReading && slot.storedPath != null -> PhotoChange.KeepWithOcr(ocr)
            else -> null
        }
    }

    override fun onCleared() {
        if (_ui.value.step != Step.SAVED) c.imageStore.clearDraft(eventId)
    }
}
