package app.tel.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tel.AppContainer
import app.tel.domain.calc.DashboardStats
import app.tel.domain.calc.Period
import app.tel.domain.calc.Statistics
import app.tel.domain.calc.TimeRange
import app.tel.domain.model.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class PeriodSelection(val period: Period = Period.ALL_TIME, val customStart: LocalDate? = null, val customEnd: LocalDate? = null)

data class DashboardUi(
    val loading: Boolean = true,
    val vehicles: List<Vehicle> = emptyList(),
    /** Null = all vehicles. */
    val selected: Vehicle? = null,
    val period: PeriodSelection = PeriodSelection(),
    /** Any refuelling at all for the selected vehicle(s), regardless of period. */
    val hasAnyEvents: Boolean = false,
    val stats: DashboardStats? = null,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    private val period = MutableStateFlow(PeriodSelection())

    val ui: StateFlow<DashboardUi> = combine(
        container.settings.settings,
        container.repository.vehicles,
        container.repository.events,
        container.repository.metrics,
        period,
    ) { settings, vehicles, events, metrics, sel ->
        val selected = settings.selectedVehicleId?.let { id -> vehicles.firstOrNull { it.id == id } }
            ?: if (settings.selectedVehicleId != null) vehicles.firstOrNull() else null
        val vehicleEvents = if (selected != null) events.filter { it.vehicleId == selected.id } else events
        val zone = ZoneId.systemDefault()
        val range = TimeRange.of(sel.period, LocalDate.now(zone), zone, sel.customStart, sel.customEnd)
        val inRange = vehicleEvents.filter { it.timestamp in range }
        val latestOdo = vehicleEvents.filter { it.odometerKm != null }.maxByOrNull { it.timestamp }?.odometerKm
        val stats = Statistics.compute(
            inRange, metrics, zone,
            latestOdometerKm = if (selected != null) latestOdo else null,
            preferredCurrency = selected?.currencyCode ?: settings.defaultCurrency,
        )
        DashboardUi(false, vehicles, selected, sel, vehicleEvents.isNotEmpty(), stats)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    fun selectVehicle(id: String?) = viewModelScope.launch { container.settings.setSelectedVehicle(id) }

    fun selectPeriod(p: Period) { period.value = period.value.copy(period = p) }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        period.value = PeriodSelection(Period.CUSTOM, start, end)
    }
}
