package app.tel.ui.dashboard

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.tel.ui.appContainer
import app.tel.ui.csv.rememberCsvActions
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tel.data.AppSettings
import app.tel.domain.calc.DashboardStats
import app.tel.domain.calc.Period
import app.tel.domain.format.Formatter
import app.tel.domain.model.Vehicle
import app.tel.domain.units.Units
import app.tel.ui.Dates
import app.tel.ui.components.ChartCard
import app.tel.ui.components.Notice
import app.tel.ui.components.SectionTitle
import app.tel.ui.components.StatCard
import app.tel.ui.components.StatRow
import app.tel.ui.containerViewModel
import app.tel.ui.theme.FigureStyle
import app.tel.ui.vehicles.Dropdown
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(settings: AppSettings, onAddRefuel: () -> Unit, onOpenEvent: (String) -> Unit) {
    val vm = containerViewModel { DashboardViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fmt = Formatter(settings.unitSystem)
    var showRange by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val container = appContainer()
    val csv = rememberCsvActions(onMessage = { m -> scope.launch { snackbar.showSnackbar(m) } })

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tel", style = MaterialTheme.typography.headlineMedium)
            SnackbarHost(snackbar)
            CsvCards(
                settings = settings,
                onSetUp = csv.setUp,
                onSnooze = { scope.launch { container.settings.snoozeCsvReminder(System.currentTimeMillis() + SNOOZE_MS) } },
                onNever = { scope.launch { container.settings.neverRemindCsv() } },
                onStop = { scope.launch { container.csvCopy.unlink() } },
            )

            when {
                ui.loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    VehicleSelector(ui.vehicles, ui.selected, settings.selectedVehicleId == null) { vm.selectVehicle(it) }
                    if (!ui.hasAnyEvents) {
                        EmptyState(onAddRefuel)
                    } else {
                        PeriodChips(ui.period.period, onSelect = {
                            if (it == Period.CUSTOM) showRange = true else vm.selectPeriod(it)
                        })
                        if (ui.period.period == Period.CUSTOM && ui.period.customStart != null) {
                            Text(
                                "${ui.period.customStart} – ${ui.period.customEnd}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = onAddRefuel, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.padding(4.dp)); Text("Add fill-up")
                        }
                        val s = ui.stats
                        if (s == null || s.refuelCount == 0) {
                            Notice("No fill-ups in this period.")
                        } else {
                            StatsBlock(s, ui.selected, fmt, onOpenEvent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showRange) {
        CustomRangeDialog(
            onDismiss = { showRange = false },
            onConfirm = { a, b -> vm.setCustomRange(a, b); showRange = false },
        )
    }
}

@Composable
private fun VehicleSelector(vehicles: List<Vehicle>, selected: Vehicle?, allSelected: Boolean, onSelect: (String?) -> Unit) {
    if (vehicles.size <= 1 && !allSelected) {
        selected?.let { Text(it.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val options: List<Vehicle?> = listOf<Vehicle?>(null) + vehicles
    Dropdown(
        label = "Vehicle",
        selected = if (allSelected) null else selected,
        options = options,
        text = { it?.name ?: "All vehicles" },
        onSelect = { onSelect(it?.id) },
    )
}

@Composable
private fun EmptyState(onAddRefuel: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No fill-ups yet.", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                "Add your first fill-up to start tracking mileage and fuel costs.",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAddRefuel, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.padding(4.dp)); Text("Add fill-up")
            }
        }
    }
}

@Composable
private fun PeriodChips(selected: Period, onSelect: (Period) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Period.entries.forEach { p ->
            FilterChip(selected = p == selected, onClick = { onSelect(p) }, label = { Text(p.label) })
        }
    }
}

@Composable
private fun StatsBlock(s: DashboardStats, vehicle: Vehicle?, fmt: Formatter, onOpenEvent: (String) -> Unit) {
    val cur = s.currencyCode
    val economyUnavailable = "Shown after two full-tank fill-ups"

    if (vehicle != null && s.latestOdometerKm != null) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(fmt.distance(s.latestOdometerKm), style = MaterialTheme.typography.displaySmall.merge(FigureStyle), color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Current odometer · ${vehicle.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }

    StatRow(
        { StatCard(s.avgKmPerL?.let { fmt.economy(it) } ?: "—", "Avg mileage", it, sub = if (s.avgKmPerL == null) economyUnavailable else null) },
        { StatCard(s.avgCostPerKm?.let { fmt.costPerDistance(it, cur) } ?: "—", "Avg cost per ${Units.distanceUnit(fmt.system)}", it) },
    )
    StatRow(
        { StatCard(fmt.money(s.totalSpend, cur, 0), "Total spend", it) },
        { StatCard(fmt.volume(s.totalFuelL, 1), "Fuel purchased", it) },
    )
    StatRow(
        { StatCard(fmt.pricePerVolume(s.avgPricePerL, cur), "Avg fuel price", it) },
        { StatCard(fmt.distance(s.totalDistanceKm), "Distance tracked", it) },
    )

    s.lastEvent?.let { last ->
        Card(
            Modifier.fillMaxWidth().clickable { onOpenEvent(last.id) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Last fill-up", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${fmt.money(last.totalCost, last.currencyCode)} · ${fmt.volume(last.fuelAddedL)}", style = MaterialTheme.typography.titleMedium.merge(FigureStyle))
                }
                Text(Dates.date(last.timestamp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    SectionTitle("Trends")
    if (s.mileageSeries.isNotEmpty()) {
        val values = s.mileageSeries.map { Units.economyToDisplay(it.value, fmt.system).toDouble() }
        ChartCard(
            title = "Mileage (${Units.economyUnit(fmt.system)})",
            values = values,
            xLabels = Dates.shortWithYear(s.mileageSeries.first().timestamp) to Dates.shortWithYear(s.mileageSeries.last().timestamp),
            format = { "%.1f".format(it) },
            summary = "Best ${fmt.economy(s.bestKmPerL)}, lowest ${fmt.economy(s.worstKmPerL)} · ${fmt.consumptionPer100(s.avgKmPerL)}",
        )
    }
    if (s.priceSeries.isNotEmpty()) {
        val values = s.priceSeries.map { Units.pricePerLToDisplay(it.value, fmt.system).toDouble() }
        ChartCard(
            title = "Fuel price (per ${Units.volumeUnit(fmt.system)})",
            values = values,
            xLabels = Dates.shortWithYear(s.priceSeries.first().timestamp) to Dates.shortWithYear(s.priceSeries.last().timestamp),
            format = { "%.2f".format(it) },
            summary = "Highest ${fmt.pricePerVolume(s.highestPricePerL, cur)}, lowest ${fmt.pricePerVolume(s.lowestPricePerL, cur)}",
        )
    }
    if (s.monthlySpend.isNotEmpty()) {
        val monthFmt = DateTimeFormatter.ofPattern("MMM yy")
        ChartCard(
            title = "Monthly fuel spend",
            values = s.monthlySpend.map { it.amount.toDouble() },
            xLabels = monthFmt.format(s.monthlySpend.first().month) to monthFmt.format(s.monthlySpend.last().month),
            format = { fmt.money(java.math.BigDecimal(it), cur, 0) },
            summary = s.monthlySpend.takeLast(3).joinToString(", ") { "${monthFmt.format(it.month)}: ${fmt.money(it.amount, cur, 0)}" },
            bars = true,
        )
    }

    SectionTitle("More figures")
    StatRow(
        { StatCard(s.refuelCount.toString(), "Fill-ups", it) },
        { StatCard(fmt.volume(s.avgFillL, 1), "Avg fill", it) },
    )
    StatRow(
        { StatCard(fmt.money(s.avgSpendPerFill, cur, 0), "Avg cost per fill", it) },
        { StatCard(s.estimatedAnnualCost?.let { fmt.money(it, cur, 0) } ?: "—", "Est. yearly cost", it, sub = if (s.estimatedAnnualCost == null) "Needs a month of data" else "At current pace") },
    )
    if (s.excludedOtherCurrency > 0) {
        Notice("${s.excludedOtherCurrency} fill-up(s) in another currency are not included in these totals.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                onClick = {
                    val a = Instant.ofEpochMilli(state.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    val b = Instant.ofEpochMilli(state.selectedEndDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(a, b)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f), showModeToggle = true)
    }
}

private const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

/** The CSV copy reminder (until set up or declined) and a warning if the file can't be updated. */
@Composable
private fun CsvCards(settings: AppSettings, onSetUp: () -> Unit, onSnooze: () -> Unit, onNever: () -> Unit, onStop: () -> Unit) {
    val csv = settings.csv
    when {
        csv.enabled && csv.error != null -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your CSV copy isn't being updated", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(csv.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSetUp) { Text("Choose a new file") }
                    TextButton(onClick = onStop) { Text("Stop copying") }
                }
            }
        }
        csv.shouldRemind(System.currentTimeMillis()) -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Keep a copy outside the app", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "Tel can keep one CSV file on your phone up to date with all your fill-ups. It stays even if you uninstall Tel, and opens in any spreadsheet.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Button(onClick = onSetUp) { Text("Set up CSV copy") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSnooze) { Text("Not now") }
                    TextButton(onClick = onNever) { Text("Don't remind me") }
                }
            }
        }
    }
}
