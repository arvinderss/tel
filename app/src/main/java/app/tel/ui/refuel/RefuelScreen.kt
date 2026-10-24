package app.tel.ui.refuel

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tel.domain.calc.EntrySource
import app.tel.domain.calc.PumpField
import app.tel.domain.format.CurrencyInfo
import app.tel.domain.format.Formatter
import app.tel.domain.model.MileageStatus
import app.tel.domain.units.Units
import app.tel.domain.validation.Fields
import app.tel.domain.validation.Severity
import app.tel.ui.Dates
import app.tel.ui.components.LocalPhoto
import app.tel.ui.components.Notice
import app.tel.ui.components.NumberField
import app.tel.ui.containerViewModel
import app.tel.ui.vehicles.Dropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelScreen(
    eventId: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onAddVehicle: () -> Unit,
) {
    val vm = containerViewModel(key = "refuel-${eventId ?: "new"}") { RefuelViewModel(it, eventId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.step) { if (ui.step == Step.SAVED) onDone() }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui.isEdit) "Edit fill-up" else "Add fill-up") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (ui.step) {
                Step.LOADING, Step.SAVING, Step.SAVED -> Column(
                    Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
                Step.FORM -> if (ui.vehicles.isEmpty()) NoVehicle(onAddVehicle) else Form(ui, vm)
            }
        }
    }

    if (ui.askConfirmWarnings) {
        AlertDialog(
            onDismissRequest = vm::dismissWarnings,
            title = { Text("Check these values") },
            text = { Text(ui.warnings.joinToString("\n\n") { "• " + it.message }) },
            confirmButton = { TextButton(onClick = { vm.save(confirmedWarnings = true) }) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = vm::dismissWarnings) { Text("Go back") } },
        )
    }
}

@Composable
private fun NoVehicle(onAddVehicle: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Notice("Add a vehicle before recording a fill-up.")
        Button(onClick = onAddVehicle, modifier = Modifier.fillMaxWidth()) { Text("Add vehicle") }
    }
}

@Composable
private fun Form(ui: RefuelUi, vm: RefuelViewModel) {
    val units = ui.unitSystem
    val fmt = Formatter(units)
    val cur = ui.vehicle?.currencyCode ?: "INR"
    val sym = CurrencyInfo.symbol(cur).trim()
    var pickDate by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.vehicles.size > 1) {
                Dropdown("Vehicle", ui.vehicle, ui.vehicles, { it?.name ?: "Choose a vehicle" }, { v -> v?.let { vm.selectVehicle(it.id) } })
            }

            // 1. Date
            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("date")) {
                Icon(Icons.Filled.DateRange, null); Spacer(Modifier.width(8.dp))
                Text(Dates.dateTime(ui.timestamp), style = MaterialTheme.typography.bodyLarge)
            }
            ui.issueFor(Fields.TIMESTAMP)?.let { Notice(it.message, warning = true) }

            // 2. Tank full
            SwitchRow(
                title = "Tank full",
                subtitle = "On when the pump clicked off. Partial fills count at the next full tank.",
                checked = ui.filledToFull,
                onChange = vm::setFilledToFull,
                tag = "switch_full",
            )

            // 3–5. Amount, rate, volume: any two give the third.
            PumpInput(ui, vm, PumpField.AMOUNT, "Amount", sym, null)
            PumpInput(ui, vm, PumpField.RATE, "Rate", "$sym/${Units.volumeUnit(units)}",
                ui.rateFrom?.takeIf { ui.pump[PumpField.RATE].source == EntrySource.PREFILLED }?.let { "From your fill-up on ${Dates.date(it)}. Change it if the price changed." })
            PumpInput(ui, vm, PumpField.VOLUME, "Volume", Units.volumeUnit(units), null)

            // 6. Odometer
            val odoIssue = ui.issueFor(Fields.ODOMETER)
            NumberField(
                label = "Odometer",
                value = ui.odometer,
                onChange = vm::setOdometer,
                modifier = Modifier.testTag("field_${Fields.ODOMETER}"),
                unit = Units.distanceUnit(units),
                error = odoIssue?.takeIf { it.severity == Severity.ERROR }?.message,
                warning = odoIssue?.takeIf { it.severity == Severity.WARNING }?.message,
                supporting = ui.previousOdometer?.let { p -> hint("Previous: ${fmt.distance(p.km)} on ${Dates.date(p.timestamp)}") },
            )

            // 7. Photos
            Photos(ui, vm)

            // 8. Notes, then the rarely used "missed a fill-up" switch.
            OutlinedTextField(ui.notes, vm::setNotes, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            SwitchRow(
                title = "Missed logging a fill-up",
                subtitle = "Turn on if you filled up since the last entry but didn't record it. Mileage restarts from here.",
                checked = ui.missedPrevious,
                onChange = vm::setMissedPrevious,
                tag = "switch_missed",
            )

            Preview(ui, fmt, cur)

            ui.issueFor(Fields.FORM)?.let { Notice(it.message, warning = true) }
            Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickDate) DateTimeDialog(ui.timestamp, onDismiss = { pickDate = false }, onPicked = { vm.setTimestamp(it); pickDate = false })
}

private fun hint(text: String): @Composable () -> Unit = { Text(text) }

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).testTag(tag).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PumpInput(ui: RefuelUi, vm: RefuelViewModel, f: PumpField, label: String, unit: String, prefillNote: String?) {
    val v = ui.pump[f]
    val issue = ui.issueFor(f.key())
    val note = when (v.source) {
        EntrySource.CALCULATED -> "Calculated from the other two. Type to override."
        EntrySource.PREFILLED -> prefillNote ?: "From your last fill-up."
        else -> null
    }
    NumberField(
        label = label,
        value = v.text,
        onChange = { vm.editPump(f, it) },
        modifier = Modifier.testTag("field_${f.key()}"),
        unit = unit,
        error = issue?.takeIf { it.severity == Severity.ERROR }?.message,
        warning = issue?.takeIf { it.severity == Severity.WARNING }?.message,
        supporting = note?.let { hint(it) },
    )
}

@Composable
private fun Photos(ui: RefuelUi, vm: RefuelViewModel) {
    val context = LocalContext.current
    var pendingCamera by rememberSaveable { mutableStateOf<String?>(null) }
    var noCamera by rememberSaveable { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf<PhotoItem?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(RefuelUi.MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) vm.addPhotos(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingCamera
        if (ok && u != null) vm.addPhotos(listOf(Uri.parse(u)))
        pendingCamera = null
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Photos (optional)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Pump display, dashboard or receipt, up to ${RefuelUi.MAX_PHOTOS}. Kept on this device only.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.photos.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.photos.forEachIndexed { i, p ->
                        Box {
                            LocalPhoto(p.file, "Photo ${i + 1}. Tap to enlarge.", Modifier.size(104.dp).clickable { enlarged = p }, maxSide = 400)
                            FilledTonalIconButton(onClick = { vm.removePhoto(p) }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp)) {
                                Icon(Icons.Filled.Close, "Remove photo ${i + 1}", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            if (ui.canAddPhoto) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        try {
                            val f = vm.newCameraFile()
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                            pendingCamera = uri.toString()
                            camera.launch(uri)
                        } catch (e: ActivityNotFoundException) {
                            noCamera = true
                        }
                    }) { Text("Take photo") }
                    OutlinedButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Choose photos") }
                }
            }
            if (noCamera) Text("No camera app found. Choose an existing photo instead.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    enlarged?.let { p ->
        Dialog(onDismissRequest = { enlarged = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LocalPhoto(p.file, "Photo", Modifier.fillMaxWidth().padding(8.dp).height(560.dp).clickable { enlarged = null }, maxSide = 1600)
        }
    }
}

@Composable
private fun Preview(ui: RefuelUi, fmt: Formatter, currency: String) {
    val p = ui.preview ?: return
    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val onC = MaterialTheme.colorScheme.onSecondaryContainer
            val d = p.distanceSincePreviousKm
            if (d != null && d.signum() > 0) Line("Since last fill-up", fmt.distance(d))
            if (p.status == MileageStatus.MEASURED) {
                Line("Mileage", fmt.economy(p.kmPerL))
                Line("Cost per ${Units.distanceUnit(fmt.system)}", fmt.costPerDistance(p.costPerKm, currency))
                if (p.spanFills > 1) Text("Over ${fmt.distance(p.spanDistanceKm)} and ${p.spanFills} fill-ups since the last full tank.", style = MaterialTheme.typography.bodySmall, color = onC)
            } else {
                Text(p.note ?: p.status.description, color = onC)
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
