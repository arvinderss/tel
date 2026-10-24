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
import app.tel.domain.model.FuelType
import app.tel.domain.model.UnitSystem
import app.tel.domain.model.Vehicle
import app.tel.ui.appContainer
import app.tel.ui.csv.rememberCsvActions
import androidx.compose.material3.OutlinedButton
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
    var units by rememberSaveable { mutableStateOf(UnitSystem.METRIC) }
    var currency by rememberSaveable { mutableStateOf("INR") }
    var saving by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val csv = rememberCsvActions(
        onMessage = { message = it },
        onRestored = {
            // Restored from a CSV kept by an earlier install: skip setup.
            val first = container.repository.allVehicles().firstOrNull()
            with(container.settings) {
                setUnitSystem(units)
                first?.let { setSelectedVehicle(it.id); setDefaultCurrency(it.currencyCode) }
                setOnboarded()
            }
        },
    )

    val canSave = name.isNotBlank() && !saving

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
            val nameHint: @Composable () -> Unit = { Text("Enter a name") }
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Vehicle name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = name.isBlank(),
                supportingText = if (name.isBlank()) nameHint else null,
            )
            FuelTypePicker(fuel) { fuel = it }
            UnitSystemPicker(units) { units = it }
            CurrencyPicker(currency) { currency = it }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                      try {
                        val id = UUID.randomUUID().toString()
                        container.repository.saveVehicle(
                            Vehicle(
                                id = id, name = name.trim(), fuelType = fuel,
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
                      } finally {
                        saving = false
                      }
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Get started") }
            OutlinedButton(onClick = csv.useExisting, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("I have a Tel CSV file from before")
            }
            Text(
                "Reinstalled Tel? Open the CSV file it kept for you to get all your fill-ups back.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
