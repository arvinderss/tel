package app.tel.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tel.data.AppSettings
import app.tel.domain.calc.Statistics
import app.tel.domain.format.Formatter
import app.tel.domain.model.MileageStatus
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.domain.units.Units
import app.tel.ui.Dates
import app.tel.ui.appContainer
import app.tel.ui.components.ConfirmDialog
import app.tel.ui.components.SectionTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(eventId: String, settings: AppSettings, onBack: () -> Unit, onEdit: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val repo = container.repository
    val event by remember(eventId) { repo.observeEvent(eventId) }.collectAsStateWithLifecycle(null)
    val vehicles by repo.vehicles.collectAsStateWithLifecycle(emptyList())
    val metrics by repo.metrics.collectAsStateWithLifecycle(emptyMap())
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var everLoaded by remember { mutableStateOf(false) }
    val fmt = Formatter(settings.unitSystem)

    LaunchedEffect(event) {
        if (event != null) everLoaded = true else if (everLoaded) onBack() // deleted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fill-up") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete fill-up") }
                },
            )
        },
    ) { padding ->
        val e = event
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (e != null) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Dates.dateTime(e.timestamp), style = MaterialTheme.typography.titleLarge)
                    Text(vehicles.firstOrNull { it.id == e.vehicleId }?.name ?: "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Values(e, fmt)
                    Calculated(metrics[e.id], e, fmt)
                    if (e.notes.isNotBlank()) { SectionTitle("Notes"); Text(e.notes) }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete this fill-up?", "It will be removed, and mileage for the following fill-ups recalculated.",
            "Delete",
            onConfirm = { confirmDelete = false; scope.launch { repo.deleteRefuel(eventId) } },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Values(e: RefuelEvent, fmt: Formatter) {
    SectionTitle("Fill-up")
    Row2("Tank full", if (e.filledToFull) "Yes" else "No, partial fill")
    Row2("Amount", fmt.money(e.totalCost, e.currencyCode))
    Row2("Rate", fmt.pricePerVolume(e.pricePerL ?: Statistics.unitPrice(e), e.currencyCode))
    Row2("Volume", fmt.volume(e.fuelAddedL))
    Row2("Odometer", fmt.distance(e.odometerKm, 0))
    if (e.missedPrevious) Row2("Missed a fill-up before this", "Yes")
}

@Composable
private fun Calculated(m: RefuelMetrics?, e: RefuelEvent, fmt: Formatter) {
    SectionTitle("Calculated")
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val d = m?.distanceSincePreviousKm
            if (d != null && d.signum() > 0) Row2("Since last fill-up", fmt.distance(d))
            if (m != null && m.status == MileageStatus.MEASURED) {
                Row2("Mileage", fmt.economy(m.kmPerL))
                Row2("Consumption", fmt.consumptionPer100(m.kmPerL))
                Row2("Cost per ${Units.distanceUnit(fmt.system)}", fmt.costPerDistance(m.costPerKm, e.currencyCode))
                Row2("Full tank to full tank", fmt.distance(m.spanDistanceKm))
                Row2("Fuel in that stretch", fmt.volume(m.spanFuelL))
                if (m.spanFills > 1) Row2("Fill-ups in that stretch", m.spanFills.toString())
            } else {
                Text("Mileage: ${m?.status?.label ?: "not available"}", style = MaterialTheme.typography.bodyLarge)
                Text(m?.note ?: m?.status?.description ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
