package app.tel.ui.refuel

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
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
            if (ui.showPhotoTip && !ui.isEdit) PhotoTip(onHide = vm::hidePhotoTip)

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

            // 7. Notes, then the rarely used "missed a fill-up" switch.
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

/** Soft, dismissible tip: no time now? Photograph the meters and enter the fill-up later. */
@Composable
private fun PhotoTip(onHide: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("No time right now?", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Take a photo of the odometer and the pump display with your camera. Add the fill-up later from the photos and set the date above.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onHide, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Don't show again") }
            }
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
