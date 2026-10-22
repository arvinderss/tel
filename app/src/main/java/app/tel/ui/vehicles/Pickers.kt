package app.tel.ui.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tel.domain.format.CurrencyInfo
import app.tel.domain.model.FuelType
import app.tel.domain.model.ThemeMode
import app.tel.domain.model.UnitSystem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    selected: T,
    options: List<T>,
    text: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); expanded = false })
            }
        }
    }
}

@Composable
fun FuelTypePicker(selected: FuelType, onSelect: (FuelType) -> Unit) =
    Dropdown("Fuel type", selected, FuelType.entries, { it.label }, onSelect)

@Composable
fun CurrencyPicker(selected: String, onSelect: (String) -> Unit) {
    val options = (CurrencyInfo.common + selected).distinct()
    Dropdown("Currency", selected, options, { "$it – ${CurrencyInfo.displayName(it)}" }, onSelect)
}

/** Radio group with labels, so the choice is announced and doesn't rely on colour. */
@Composable
fun <T> RadioGroup(title: String, selected: T, options: List<T>, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = o == selected, onClick = { onSelect(o) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = o == selected, onClick = null)
                Text(text(o), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun UnitSystemPicker(selected: UnitSystem, onSelect: (UnitSystem) -> Unit) =
    RadioGroup("Units", selected, UnitSystem.entries, {
        if (it == UnitSystem.METRIC) "Metric (km, litres, km/L)" else "US (miles, US gallons, MPG)"
    }, onSelect)

@Composable
fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) =
    RadioGroup("Appearance", selected, ThemeMode.entries, {
        when (it) { ThemeMode.SYSTEM -> "Follow system"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }
    }, onSelect)
