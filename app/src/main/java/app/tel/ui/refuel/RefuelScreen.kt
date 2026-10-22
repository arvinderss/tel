package app.tel.ui.refuel

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tel.data.AppSettings
import app.tel.domain.format.CurrencyInfo
import app.tel.domain.format.Formatter
import app.tel.domain.model.CalculationQuality
import app.tel.domain.model.ImageType
import app.tel.domain.model.ValueSource
import app.tel.domain.units.Units
import app.tel.ui.Dates
import app.tel.ui.components.LocalPhoto
import app.tel.ui.components.Notice
import app.tel.ui.components.NumberField
import app.tel.ui.components.SectionTitle
import app.tel.ui.components.SourceTag
import app.tel.ui.containerViewModel
import app.tel.ui.vehicles.Dropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelScreen(
    eventId: String?,
    reprocess: Boolean,
    settings: AppSettings,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onAddVehicle: () -> Unit,
) {
    val vm = containerViewModel(key = "refuel-${eventId ?: "new"}-$reprocess") { RefuelViewModel(it, eventId, reprocess) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.step) { if (ui.step == Step.SAVED) onDone() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui.isEdit) "Edit refuelling" else "Add refuelling") },
                navigationIcon = {
                    IconButton(onClick = { if (ui.step == Step.REVIEW && !ui.isEdit) vm.backToPhotos() else onCancel() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (ui.step) {
                Step.LOADING, Step.SAVING, Step.SAVED -> Busy(if (ui.step == Step.SAVING) "Saving…" else "Loading…")
                Step.PROCESSING -> Busy(ui.processingLabel)
                Step.PHOTOS -> PhotosStep(ui, vm, onAddVehicle)
                Step.REVIEW -> ReviewStep(ui, vm, settings)
            }
        }
    }

    if (ui.askConfirmWarnings) {
        AlertDialog(
            onDismissRequest = vm::dismissWarnings,
            title = { Text("Check these values") },
            text = { Text(ui.warnings.joinToString("\n\n") { "• " + it.message }) },
            confirmButton = { TextButton(onClick = { vm.save(confirmedWarnings = true) }) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = vm::dismissWarnings) { Text("Review") } },
        )
    }
}

@Composable
private fun Busy(label: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text("Everything is processed on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun VehiclePicker(ui: RefuelUi, vm: RefuelViewModel) {
    if (ui.vehicles.size > 1) {
        Dropdown("Vehicle", ui.vehicle, ui.vehicles, { it?.name ?: "Choose a vehicle" }, { v -> v?.let { vm.selectVehicle(it.id) } })
    } else {
        ui.vehicle?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
    }
}

// ------------------------------------------------------------------ step 1: photos

@Composable
private fun PhotosStep(ui: RefuelUi, vm: RefuelViewModel, onAddVehicle: () -> Unit) {
    FormColumn {
        if (ui.vehicles.isEmpty()) {
            Notice("Add a vehicle before recording a refuelling.")
            Button(onClick = onAddVehicle, modifier = Modifier.fillMaxWidth()) { Text("Add vehicle") }
        } else {
            PhotoList(ui, vm)
        }
    }
}

@Composable
private fun PhotoList(ui: RefuelUi, vm: RefuelViewModel) {
    run {
        VehiclePicker(ui, vm)
        Text(
            "Photograph the dashboard before and after filling, and the pump display. All photos are optional.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ImageType.entries.forEachIndexed { i, type ->
            PhotoSlotCard(index = i + 1, slot = ui.photos.getValue(type), vm = vm)
        }
        val any = ui.photos.values.any { it.present }
        Button(onClick = { vm.processPhotos() }, enabled = any, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Read photos")
        }
        TextButton(onClick = vm::skipToManual, modifier = Modifier.fillMaxWidth()) {
            Text(if (any) "Skip reading and enter values manually" else "Enter values manually")
        }
    }
}

@Composable
private fun PhotoSlotCard(index: Int, slot: PhotoSlot, vm: RefuelViewModel) {
    val context = LocalContext.current
    var pendingCamera by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraUnavailable by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addPhoto(slot.type, uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingCamera
        if (ok && u != null) vm.addPhoto(slot.type, Uri.parse(u))
        pendingCamera = null
    }

    val title = when (slot.type) {
        ImageType.BEFORE_HUD -> "Before refuelling"
        ImageType.AFTER_HUD -> "After refuelling"
        ImageType.PUMP_METER -> "Petrol station meter"
    }
    val hint = when (slot.type) {
        ImageType.BEFORE_HUD -> "Dashboard: odometer and fuel level"
        ImageType.AFTER_HUD -> "Dashboard after filling"
        ImageType.PUMP_METER -> "Pump display: amount, litres, price"
    }

    Card(
        Modifier.fillMaxWidth().semantics { stateDescription = if (slot.present) "Photo added" else "No photo" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$index. ${title.uppercase()}", style = MaterialTheme.typography.titleSmall)
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (slot.present) {
                    IconButton(onClick = { vm.removePhoto(slot.type) }) { Icon(Icons.Filled.Close, "Remove $title photo") }
                }
            }
            if (slot.present) {
                LocalPhoto(slot.file, "$title photo", Modifier.fillMaxWidth().height(160.dp))
                slot.reading?.problem?.let { Notice(it, warning = true) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try {
                        val f = vm.newCameraFile()
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                        pendingCamera = uri.toString()
                        camera.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        cameraUnavailable = true
                    }
                }) { Text(if (slot.present) "Retake" else "Take photo") }
                OutlinedButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (slot.present) "Choose another" else "Choose photo") }
            }
            if (cameraUnavailable) Text("No camera app found. Choose an existing photo instead.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ------------------------------------------------------------------ step 2: review

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(ui: RefuelUi, vm: RefuelViewModel, settings: AppSettings) {
    val fmt = Formatter(ui.unitSystem)
    val units = ui.unitSystem
    val cur = ui.vehicle?.currencyCode ?: settings.defaultCurrency
    val sym = CurrencyInfo.symbol(cur).trim()
    var pickDate by rememberSaveable { mutableStateOf(false) }

    FormColumn {
        Text("Review refuelling", style = MaterialTheme.typography.titleLarge)
        Text(
            "Check every value. Tap a value to correct it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VehiclePicker(ui, vm)
        OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.DateRange, null); Spacer(Modifier.width(8.dp)); Text(Dates.dateTime(ui.timestamp))
        }
        ui.photos.values.mapNotNull { s -> s.reading?.problem?.let { s.type to it } }.forEach { (t, p) ->
            Notice("${t.label}: $p", warning = true)
        }

        SectionTitle("Before refuelling")
        ReviewField(ui, vm, FormField.ODO_BEFORE, Units.distanceUnit(units))
        ReviewField(ui, vm, FormField.LEVEL_BEFORE, "%")

        SectionTitle("After refuelling")
        ReviewField(ui, vm, FormField.ODO_AFTER, Units.distanceUnit(units), hint = "Leave empty if it's the same as before.")
        ReviewField(ui, vm, FormField.LEVEL_AFTER, "%")

        SectionTitle("Petrol station")
        ReviewField(ui, vm, FormField.FUEL_ADDED, Units.volumeUnit(units))
        ReviewField(ui, vm, FormField.PRICE, "$sym/${Units.volumeUnit(units)}")
        ReviewField(ui, vm, FormField.TOTAL, sym)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Filled the tank to full", style = MaterialTheme.typography.bodyLarge)
                Text("Pump clicked off. Gives the most accurate mileage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.filledToFull, onCheckedChange = vm::setFilledToFull)
        }

        CalculatedCard(ui, fmt, cur)

        OutlinedTextField(ui.notes, vm::setNotes, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

        if (!ui.isEdit || ui.photos.values.any { it.present }) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.photos.values.filter { it.present }.forEach { LocalPhoto(it.file, it.type.label, Modifier.size(96.dp), maxSide = 300) }
            }
            TextButton(onClick = vm::backToPhotos) { Text("Change photos") }
        }

        if (ui.errors.isNotEmpty()) Notice("Fix ${ui.errors.size} value(s) marked in red before saving.", warning = true)
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save refuelling") }
        Spacer(Modifier.height(24.dp))
    }

    if (pickDate) DateTimeDialog(ui.timestamp, onDismiss = { pickDate = false }, onPicked = { vm.setTimestamp(it); pickDate = false })
}

@Composable
private fun ReviewField(ui: RefuelUi, vm: RefuelViewModel, f: FormField, unit: String, hint: String? = null) {
    val state = ui.field(f)
    val issue = ui.issueFor(f)
    val hintContent: @Composable () -> Unit = { Text(hint.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NumberField(
            label = f.label,
            value = state.text,
            onChange = { vm.edit(f, it) },
            modifier = Modifier.testTag("field_${f.key}"),
            unit = unit,
            error = issue?.takeIf { it.severity == app.tel.domain.validation.Severity.ERROR }?.message,
            warning = issue?.takeIf { it.severity == app.tel.domain.validation.Severity.WARNING }?.message,
            supporting = if (hint != null) hintContent else null,
        )
        if (state.source != ValueSource.EMPTY) SourceTag(state.source, state.needsReview, state.confidence)
        if (state.candidates.isNotEmpty()) {
            Text("Other numbers in the photo:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.candidates.forEach { c ->
                    AssistChip(onClick = { vm.useCandidate(f, c) }, label = { Text(c.value.toPlainString()) })
                }
            }
        }
    }
}

@Composable
private fun CalculatedCard(ui: RefuelUi, fmt: Formatter, currency: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Calculated", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            val p = ui.preview
            if (ui.isFirstForVehicle) {
                Text("Mileage will be available after the next refuelling.", color = MaterialTheme.colorScheme.onSecondaryContainer)
            } else if (p == null || p.kmPerL == null) {
                val d = p?.distanceKm
                if (d != null && d.signum() > 0) Line("Distance since last refuelling", fmt.distance(d))
                Text("Mileage unavailable: ${p?.note ?: "not enough data"}", color = MaterialTheme.colorScheme.onSecondaryContainer)
            } else {
                run {
                    Line("Distance since last refuelling", fmt.distance(p.distanceKm))
                    Line("Fuel used", fmt.volume(p.fuelConsumedL))
                    Line("Mileage", fmt.economy(p.kmPerL))
                    Line("Cost per ${Units.distanceUnit(fmt.system)}", fmt.costPerDistance(p.costPerKm, currency))
                    Line("Quality", p.quality.label)
                    if (p.quality != CalculationQuality.MEASURED) {
                        Text(p.quality.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
