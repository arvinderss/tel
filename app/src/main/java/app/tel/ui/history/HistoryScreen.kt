package app.tel.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tel.data.AppSettings
import app.tel.domain.calc.Statistics
import app.tel.domain.format.Formatter
import app.tel.domain.model.CalculationQuality
import app.tel.domain.model.RefuelEvent
import app.tel.domain.model.RefuelMetrics
import app.tel.domain.model.Vehicle
import app.tel.ui.Dates
import app.tel.ui.appContainer
import app.tel.ui.theme.FigureStyle
import app.tel.ui.vehicles.Dropdown

enum class HistorySort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), COST("Highest cost"), MILEAGE("Best mileage") }

@Composable
fun HistoryScreen(settings: AppSettings, onOpenEvent: (String) -> Unit, onAddRefuel: () -> Unit) {
    val container = appContainer()
    val vehicles by container.repository.vehicles.collectAsStateWithLifecycle(emptyList())
    val events by container.repository.events.collectAsStateWithLifecycle(emptyList())
    val metrics by container.repository.metrics.collectAsStateWithLifecycle(emptyMap())
    var vehicleFilter by rememberSaveable { mutableStateOf(settings.selectedVehicleId) }
    var sort by rememberSaveable { mutableStateOf(HistorySort.NEWEST) }
    val fmt = Formatter(settings.unitSystem)

    val shown = remember(events, metrics, vehicleFilter, sort) {
        val list = events.filter { vehicleFilter == null || it.vehicleId == vehicleFilter }
        when (sort) {
            HistorySort.NEWEST -> list.sortedByDescending { it.timestamp }
            HistorySort.OLDEST -> list.sortedBy { it.timestamp }
            HistorySort.COST -> list.sortedByDescending { it.totalCost }
            HistorySort.MILEAGE -> list.sortedByDescending { metrics[it.id]?.kmPerL }
        }
    }
    val names = vehicles.associate { it.id to it.name }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("History", style = MaterialTheme.typography.headlineMedium)
                if (vehicles.size > 1) {
                    val options: List<Vehicle?> = listOf<Vehicle?>(null) + vehicles
                    Dropdown("Vehicle", vehicles.firstOrNull { it.id == vehicleFilter }, options, { it?.name ?: "All vehicles" }, { vehicleFilter = it?.id })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistorySort.entries.forEach { s -> FilterChip(selected = s == sort, onClick = { sort = s }, label = { Text(s.label) }) }
                }
                Text("${shown.size} refuelling(s)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (shown.isEmpty()) {
            item {
                Column(Modifier.padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No refuelling records yet.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAddRefuel) { Text("+ Add refuelling") }
                }
            }
        }
        items(shown, key = { it.id }) { e ->
            HistoryRow(e, metrics[e.id], if (vehicleFilter == null && vehicles.size > 1) names[e.vehicleId] else null, fmt) { onOpenEvent(e.id) }
        }
    }
}

@Composable
private fun HistoryRow(e: RefuelEvent, m: RefuelMetrics?, vehicleName: String?, fmt: Formatter, onClick: () -> Unit) {
    val mileage = m?.kmPerL?.let { fmt.economy(it) } ?: if (m?.isFirst == true) "First fill" else "—"
    val estimated = m?.kmPerL != null && m.quality != CalculationQuality.MEASURED
    val summary = "${Dates.date(e.timestamp)}, ${fmt.money(e.totalCost, e.currencyCode)}, ${fmt.volume(e.fuelAddedL)}, mileage $mileage"
    Card(
        Modifier.widthIn(max = 900.dp).fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = summary },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Dates.date(e.timestamp), style = MaterialTheme.typography.titleSmall)
                val line2 = listOfNotNull(vehicleName, e.readingAtPumpKm?.let { fmt.distance(it) }, fmt.volume(e.fuelAddedL),
                    Statistics.unitPrice(e)?.let { fmt.pricePerVolume(it, e.currencyCode) })
                Text(line2.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt.money(e.totalCost, e.currencyCode), style = MaterialTheme.typography.titleSmall.merge(FigureStyle))
                Text(mileage + if (estimated) " (est.)" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

