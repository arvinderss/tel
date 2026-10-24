package app.tel.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tel.BuildConfig
import app.tel.data.AppSettings
import app.tel.data.TransferException
import app.tel.ui.csv.rememberCsvActions
import app.tel.ui.Dates
import app.tel.ui.appContainer
import app.tel.ui.components.Notice
import app.tel.ui.components.SectionTitle
import app.tel.ui.vehicles.CurrencyPicker
import app.tel.ui.vehicles.FuelTypePicker
import app.tel.ui.vehicles.ThemePicker
import app.tel.ui.vehicles.UnitSystemPicker
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(settings: AppSettings, onManageVehicles: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    val csvActions = rememberCsvActions(onMessage = { m -> scope.launch { snackbar.showSnackbar(m) } })
    var confirmWipe by rememberSaveable { mutableStateOf(false) }
    var wipeText by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now().toString()

    fun perform(label: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            val msg = try { block(); label } catch (e: TransferException) { e.message ?: "Failed." } catch (t: Throwable) { "Something went wrong. Nothing was changed." }
            busy = false
            snackbar.showSnackbar(msg)
        }
    }

    fun output(uri: Uri?, label: String, write: suspend (java.io.OutputStream) -> Unit) {
        if (uri == null) return
        perform(label) {
            context.contentResolver.openOutputStream(uri)?.use { write(it) } ?: throw TransferException("Couldn't open that file.")
        }
    }

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { output(it, "CSV exported.") { o -> container.transfer.exportCsv(o) } }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { output(it, "JSON exported.") { o -> container.transfer.exportJson(o) } }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            SnackbarHost(snackbar)

            SectionTitle("Vehicles")
            OutlinedButton(onClick = onManageVehicles, modifier = Modifier.fillMaxWidth()) { Text("Manage vehicles") }

            SectionTitle("Units")
            UnitSystemPicker(settings.unitSystem) { u -> scope.launch { container.settings.setUnitSystem(u) } }
            Text(
                "Values are stored exactly; switching only changes how they are shown.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Defaults for new vehicles")
            CurrencyPicker(settings.defaultCurrency) { c -> scope.launch { container.settings.setDefaultCurrency(c) } }
            FuelTypePicker(settings.defaultFuelType) { f -> scope.launch { container.settings.setDefaultFuelType(f) } }

            SectionTitle("Appearance")
            ThemePicker(settings.theme) { t -> scope.launch { container.settings.setTheme(t) } }

            SectionTitle("Your data")
            CsvCopyCard(
                settings = settings,
                busy = busy,
                onSetUp = csvActions.setUp,
                onUseExisting = csvActions.useExisting,
                onSaveNow = { perform("CSV file updated.") { if (!container.csvCopy.writeNow()) throw TransferException("Couldn't update the CSV file.") } },
                onStop = { perform("Stopped updating the CSV file. The file itself is unchanged.") { container.csvCopy.unlink() } },
            )
            Text("Other files (saved where you choose, never uploaded):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !busy, onClick = { csv.launch("tel-export-$today.csv") }, modifier = Modifier.fillMaxWidth()) { Text("Export a CSV now") }
            OutlinedButton(enabled = !busy, onClick = { json.launch("tel-data-$today.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export all data (JSON)") }
            OutlinedButton(enabled = !busy, onClick = csvActions.restoreOnly, modifier = Modifier.fillMaxWidth()) { Text("Restore from a file") }
            Text("A Tel CSV file, or a .zip backup from an earlier version.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            OutlinedButton(enabled = !busy, onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete all data", color = MaterialTheme.colorScheme.error)
            }

            SectionTitle("Privacy")
            Notice("Tel has no internet permission, no account, no analytics and no ads. Your fill-ups never leave this device unless you export them.")
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false; wipeText = "" },
            title = { Text("Delete all data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val keep = settings.csv.fileName?.let { "Your CSV copy ($it) is not changed, but Tel stops updating it. " } ?: ""
                    Text("This permanently deletes every vehicle, fill-up and setting in Tel. ${keep}Type DELETE to confirm.")
                    OutlinedTextField(wipeText, { wipeText = it }, singleLine = true, label = { Text("Type DELETE") })
                }
            },
            confirmButton = {
                TextButton(enabled = wipeText.trim() == "DELETE", onClick = {
                    confirmWipe = false; wipeText = ""
                    perform("All data deleted.") { container.csvCopy.unlink(); container.repository.deleteAll() }
                }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false; wipeText = "" }) { Text("Cancel") } },
        )
    }
}


@Composable
private fun CsvCopyCard(
    settings: AppSettings,
    busy: Boolean,
    onSetUp: () -> Unit,
    onUseExisting: () -> Unit,
    onSaveNow: () -> Unit,
    onStop: () -> Unit,
) {
    val csv = settings.csv
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CSV copy", style = MaterialTheme.typography.titleSmall)
            if (csv.enabled) {
                Text(
                    "Saving to ${csv.fileName ?: "your CSV file"}" + (csv.lastWritten?.let { " · updated ${Dates.dateTime(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                csv.error?.let { Notice(it, warning = true) }
                Text(
                    "Tel rewrites this file after every change. Edits you make to it elsewhere are replaced; to bring them in, use \"Restore from a file\" first.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = onSaveNow) { Text("Update now") }
                    OutlinedButton(enabled = !busy, onClick = if (csv.error != null) onSetUp else onStop) {
                        Text(if (csv.error != null) "Choose a new file" else "Stop updating")
                    }
                }
                if (csv.error != null) TextButton(onClick = onStop) { Text("Stop updating") }
            } else {
                Text(
                    "Keep all your vehicles and fill-ups in one CSV file on your phone. Tel updates it after every change; it survives uninstalling Tel and opens in any spreadsheet. Tel can only see that one file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(enabled = !busy, onClick = onSetUp, modifier = Modifier.fillMaxWidth()) { Text("Create CSV file") }
                OutlinedButton(enabled = !busy, onClick = onUseExisting, modifier = Modifier.fillMaxWidth()) { Text("Use an existing Tel CSV") }
            }
        }
    }
}
