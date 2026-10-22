package app.tel.ui.vehicles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tel.data.AppSettings
import app.tel.domain.format.Formatter
import app.tel.domain.format.Input
import app.tel.domain.model.Vehicle
import app.tel.domain.units.Units
import app.tel.ui.appContainer
import app.tel.ui.components.ConfirmDialog
import app.tel.ui.components.NumberField
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleListScreen(settings: AppSettings, onBack: () -> Unit, onEdit: (String) -> Unit, onAdd: () -> Unit) {
    val container = appContainer()
    val vehicles by container.repository.vehicles.collectAsStateWithLifecycle(emptyList())
    val fmt = Formatter(settings.unitSystem)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My vehicles") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add vehicle") })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(vehicles, key = { it.id }) { v ->
                Card(
                    Modifier.fillMaxWidth().clickable { onEdit(v.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = MaterialTheme.typography.titleMedium)
                            val details = listOfNotNull(
                                v.fuelType.label,
                                v.tankCapacityL?.let { "${fmt.volume(it, 0)} tank" },
                                v.currencyCode,
                                listOf(v.make, v.model).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() },
                            )
                            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (settings.selectedVehicleId == v.id) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected on dashboard", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditScreen(vehicleId: String?, settings: AppSettings, onDone: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val units = settings.unitSystem

    var loaded by rememberSaveable { mutableStateOf(vehicleId == null) }
    var name by rememberSaveable { mutableStateOf("") }
    var make by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var fuel by rememberSaveable { mutableStateOf(settings.defaultFuelType) }
    var tank by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf(settings.defaultCurrency) }
    var registration by rememberSaveable { mutableStateOf("") }
    var createdAt by rememberSaveable { mutableStateOf(0L) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var eventCount by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(vehicleId) {
        if (vehicleId != null && !loaded) {
            container.repository.vehicle(vehicleId)?.let { v ->
                name = v.name; make = v.make; model = v.model; year = v.year?.toString().orEmpty()
                fuel = v.fuelType; currency = v.currencyCode; registration = v.registration; createdAt = v.createdAt
                tank = Input.format(v.tankCapacityL?.let { Units.litresToDisplay(it, units) }, 1)
            }
            eventCount = container.repository.eventsFor(vehicleId).size
            loaded = true
        }
    }

    val tankValue = Input.parse(tank)
    val tankError = when {
        Input.isInvalid(tank) -> "Enter a number"
        tankValue != null && tankValue.signum() <= 0 -> "Must be more than zero"
        else -> null
    }
    val yearValue = year.toIntOrNull()
    val yearError = if (year.isNotBlank() && (yearValue == null || yearValue !in 1900..2100)) "Enter a year like 2019" else null
    val canSave = loaded && name.isNotBlank() && tankError == null && yearError == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vehicleId == null) "Add vehicle" else "Edit vehicle") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Vehicle name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = loaded && name.isBlank())
                FuelTypePicker(fuel) { fuel = it }
                NumberField("Fuel tank capacity", tank, { tank = it }, unit = Units.volumeUnit(units), error = tankError)
                CurrencyPicker(currency) { currency = it }
                OutlinedTextField(make, { make = it }, label = { Text("Make (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("Model (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val yearHint: @Composable () -> Unit = { Text(yearError.orEmpty()) }
                OutlinedTextField(
                    year, { s -> year = s.filter { it.isDigit() }.take(4) }, label = { Text("Year (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = yearError != null,
                    supportingText = if (yearError != null) yearHint else null, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(registration, { registration = it }, label = { Text("Registration or nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = canSave,
                    onClick = {
                        scope.launch {
                            val id = vehicleId ?: UUID.randomUUID().toString()
                            container.repository.saveVehicle(
                                Vehicle(
                                    id = id, name = name.trim(), make = make.trim(), model = model.trim(), year = yearValue,
                                    fuelType = fuel, tankCapacityL = tankValue?.let { Units.displayToLitres(it, units) },
                                    currencyCode = currency, registration = registration.trim(), createdAt = createdAt,
                                ),
                            )
                            if (vehicleId == null && settings.selectedVehicleId == null) container.settings.setSelectedVehicle(id)
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Save vehicle") }
                if (vehicleId != null) {
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Delete vehicle", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmDelete && vehicleId != null) {
        ConfirmDialog(
            title = "Delete ${name.ifBlank { "vehicle" }}?",
            message = "This permanently deletes the vehicle, its $eventCount refuelling record(s) and their photos. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    container.repository.deleteVehicle(vehicleId)
                    if (settings.selectedVehicleId == vehicleId) {
                        container.settings.setSelectedVehicle(container.repository.allVehicles().firstOrNull()?.id)
                    }
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
