package app.tel.ui.detail

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tel.data.AppSettings
import app.tel.data.StoredImage
import app.tel.domain.format.Formatter
import app.tel.domain.model.ImageType
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.ui.Dates
import app.tel.ui.appContainer
import app.tel.ui.components.ConfirmDialog
import app.tel.ui.components.LocalPhoto
import app.tel.ui.components.Notice
import app.tel.ui.components.SectionTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(eventId: String, settings: AppSettings, onBack: () -> Unit, onEdit: () -> Unit, onReprocess: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val repo = container.repository
    val event by remember(eventId) { repo.observeEvent(eventId) }.collectAsStateWithLifecycle(null)
    val images by remember(eventId) { repo.observeImages(eventId) }.collectAsStateWithLifecycle(emptyList())
    val ocr by remember(eventId) { repo.observeOcr(eventId) }.collectAsStateWithLifecycle(emptyList())
    val vehicles by repo.vehicles.collectAsStateWithLifecycle(emptyList())
    val metrics by repo.metrics.collectAsStateWithLifecycle(emptyMap())
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deletePhoto by rememberSaveable { mutableStateOf<ImageType?>(null) }
    var viewPhoto by remember { mutableStateOf<StoredImage?>(null) }
    var everLoaded by remember { mutableStateOf(false) }
    val fmt = Formatter(settings.unitSystem)

    LaunchedEffect(event) {
        if (event != null) everLoaded = true else if (everLoaded) onBack() // deleted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Refuelling") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete refuelling") }
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
                    val vehicle = vehicles.firstOrNull { it.id == e.vehicleId }
                    Text(Dates.dateTime(e.timestamp), style = MaterialTheme.typography.titleLarge)
                    Text(vehicle?.name ?: "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Values(e, fmt)
                    Calculated(metrics[e.id], e, fmt)
                    if (ocr.isNotEmpty()) {
                        SectionTitle("Read from photos")
                        ocr.sortedBy { it.fieldName }.forEach { r ->
                            Row2(
                                r.fieldName.replace("_", " ").replace(".", " · "),
                                "${r.rawText.ifBlank { r.parsedValue?.toPlainString() ?: "—" }} · ${(r.confidence * 100).toInt()}% confidence",
                            )
                        }
                    }
                    if (e.notes.isNotBlank()) { SectionTitle("Notes"); Text(e.notes) }

                    SectionTitle("Photos")
                    if (images.isEmpty()) Text("No photos stored.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    images.sortedBy { it.type.ordinal }.forEach { img ->
                        Text(img.type.label, style = MaterialTheme.typography.labelLarge)
                        LocalPhoto(img.file, "${img.type.label} photo. Tap to enlarge.", Modifier.fillMaxWidth().height(180.dp).clickable { viewPhoto = img })
                        TextButton(onClick = { deletePhoto = img.type }) { Text("Delete this photo", color = MaterialTheme.colorScheme.error) }
                    }
                    if (images.isNotEmpty()) {
                        OutlinedButton(onClick = onReprocess, modifier = Modifier.fillMaxWidth()) { Text("Read photos again") }
                        Text(
                            "Values you typed yourself are kept; the photo's reading is offered next to them.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete this refuelling?", "Its values and photos will be removed. Mileage for the next refuelling will be recalculated.",
            "Delete",
            onConfirm = { confirmDelete = false; scope.launch { repo.deleteRefuel(eventId) } },
            onDismiss = { confirmDelete = false },
        )
    }
    deletePhoto?.let { t ->
        ConfirmDialog(
            "Delete the ${t.label.lowercase()} photo?", "The values already saved are kept. Only the photo is deleted.",
            "Delete photo",
            onConfirm = { deletePhoto = null; scope.launch { repo.deleteImage(eventId, t) } },
            onDismiss = { deletePhoto = null },
        )
    }
    viewPhoto?.let { img ->
        Dialog(onDismissRequest = { viewPhoto = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LocalPhoto(img.file, "${img.type.label} photo", Modifier.fillMaxWidth().padding(8.dp).height(520.dp).clickable { viewPhoto = null }, maxSide = 1600)
        }
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
    SectionTitle("Before refuelling")
    Row2("Odometer", fmt.distance(e.odometerBeforeKm, 1))
    Row2("Fuel level", fmt.percent(e.fuelLevelBeforePct))
    SectionTitle("After refuelling")
    Row2("Odometer", fmt.distance(e.odometerAfterKm, 1))
    Row2("Fuel level", fmt.percent(e.fuelLevelAfterPct))
    SectionTitle("Petrol station")
    Row2("Fuel added", fmt.volume(e.fuelAddedL))
    Row2("Price", fmt.pricePerVolume(e.pricePerL, e.currencyCode))
    Row2("Total paid", fmt.money(e.totalCost, e.currencyCode))
    Row2("Filled to full", if (e.filledToFull) "Yes" else "No")
}

@Composable
private fun Calculated(m: RefuelMetrics?, e: RefuelEvent, fmt: Formatter) {
    SectionTitle("Calculated")
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (m == null || m.isFirst) {
                Text("Mileage: available after the next refuelling.")
            } else {
                Row2("Distance", fmt.distance(m.distanceKm))
                Row2("Fuel used", fmt.volume(m.fuelConsumedL))
                Row2("Mileage", fmt.economy(m.kmPerL))
                Row2("Consumption", fmt.consumptionPer100(m.kmPerL))
                Row2("Cost per distance", fmt.costPerDistance(m.costPerKm, e.currencyCode))
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row2("Calculation quality", m.quality.label)
                Row2("Method", m.method.label)
                Text(m.quality.description, style = MaterialTheme.typography.bodySmall)
                m.note?.let { Notice(it) }
            }
        }
    }
}
