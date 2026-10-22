package app.tel.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tel.domain.format.Input
import app.tel.domain.model.FuelType
import app.tel.domain.model.UnitSystem
import app.tel.domain.model.Vehicle
import app.tel.domain.units.Units
import app.tel.ui.appContainer
import app.tel.ui.components.NumberField
import app.tel.ui.vehicles.CurrencyPicker
import app.tel.ui.vehicles.FuelTypePicker
import app.tel.ui.vehicles.UnitSystemPicker
import kotlinx.coroutines.launch
import java.util.UUID

/** First launch: one short form, no account, then straight to the dashboard. */
@Composable
fun OnboardingScreen() {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("My Car") }
    var fuel by rememberSaveable { mutableStateOf(FuelType.PETROL) }
    var tank by rememberSaveable { mutableStateOf("40") }
    var units by rememberSaveable { mutableStateOf(UnitSystem.METRIC) }
    var currency by rememberSaveable { mutableStateOf("INR") }
    var saving by rememberSaveable { mutableStateOf(false) }

    val tankValue = Input.parse(tank)
    val tankError = when {
        Input.isInvalid(tank) -> "Enter a number"
        tankValue != null && tankValue.signum() <= 0 -> "Must be more than zero"
        else -> null
    }
    val canSave = name.isNotBlank() && tankError == null && !saving

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Welcome to Tel", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Let's set up your vehicle. Everything stays on this device: no account, no internet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Vehicle name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = name.isBlank(),
                supportingText = if (name.isBlank()) @Composable { Text("Enter a name") } else null,
            )
            FuelTypePicker(fuel) { fuel = it }
            UnitSystemPicker(units) { units = it }
            NumberField(
                label = "Fuel tank capacity", value = tank, onChange = { tank = it },
                unit = Units.volumeUnit(units), error = tankError,
                supporting = { Text("Optional, but needed for gauge-based estimates.") },
            )
            CurrencyPicker(currency) { currency = it }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val id = UUID.randomUUID().toString()
                        container.repository.saveVehicle(
                            Vehicle(
                                id = id, name = name.trim(), fuelType = fuel,
                                tankCapacityL = tankValue?.let { Units.displayToLitres(it, units) },
                                currencyCode = currency,
                            ),
                        )
                        with(container.settings) {
                            setUnitSystem(units)
                            setDefaultCurrency(currency)
                            setDefaultFuelType(fuel)
                            setSelectedVehicle(id)
                            setOnboarded()
                        }
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Get started") }
        }
    }
}
