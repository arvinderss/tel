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
import app.tel.data.BackupException
import app.tel.data.BackupPreview
import app.tel.data.OcrSensitivity
import app.tel.data.RestoreMode
import app.tel.ui.Dates
import app.tel.ui.appContainer
import app.tel.ui.components.Notice
import app.tel.ui.components.SectionTitle
import app.tel.ui.vehicles.CurrencyPicker
import app.tel.ui.vehicles.FuelTypePicker
import app.tel.ui.vehicles.RadioGroup
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
    var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var confirmWipe by rememberSaveable { mutableStateOf(false) }
    var wipeText by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now().toString()

    fun perform(label: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            val msg = try { block(); label } catch (e: BackupException) { e.message ?: "Failed." } catch (t: Throwable) { "Something went wrong. Nothing was changed." }
            busy = false
            snackbar.showSnackbar(msg)
        }
    }

    fun output(uri: Uri?, label: String, write: suspend (java.io.OutputStream) -> Unit) {
        if (uri == null) return
        perform(label) {
            context.contentResolver.openOutputStream(uri)?.use { write(it) } ?: throw BackupException("Couldn't open that file.")
        }
    }

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { output(it, "CSV exported.") { o -> container.backup.exportCsv(o) } }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { output(it, "JSON exported.") { o -> container.backup.exportJson(o) } }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { output(it, "Backup saved.") { o -> container.backup.exportBackup(o) } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreUri = uri.toString()
            perform("Backup checked.") {
                preview = container.backup.preview { context.contentResolver.openInputStream(uri) ?: throw BackupException("Couldn't open that file.") }
            }
        }
    }

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

            SectionTitle("Photo reading (OCR)")
            RadioGroup("Mark values as \"Needs review\"", settings.ocrSensitivity, OcrSensitivity.entries, { it.label }) { s ->
                scope.launch { container.settings.setOcrSensitivity(s) }
            }
            Text(
                "Reading runs on this device with a built-in model for Latin-script text and digits. Nothing is sent anywhere.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Appearance")
            ThemePicker(settings.theme) { t -> scope.launch { container.settings.setTheme(t) } }

            SectionTitle("Your data")
            Text("Files are saved where you choose. Nothing is uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !busy, onClick = { zip.launch("tel-backup-$today.zip") }, modifier = Modifier.fillMaxWidth()) { Text("Back up (data + photos)") }
            OutlinedButton(enabled = !busy, onClick = { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
            OutlinedButton(enabled = !busy, onClick = { csv.launch("tel-history-$today.csv") }, modifier = Modifier.fillMaxWidth()) { Text("Export history (CSV)") }
            OutlinedButton(enabled = !busy, onClick = { json.launch("tel-data-$today.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export all data (JSON)") }
            HorizontalDivider()
            OutlinedButton(enabled = !busy, onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete all data", color = MaterialTheme.colorScheme.error)
            }

            SectionTitle("Privacy")
            Notice("Tel has no internet permission, no account, no analytics and no ads. Your records and photos never leave this device unless you export them.")
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Created: ${Dates.dateTime(p.createdAt)}\nVehicles: ${p.vehicles}\nRefuellings: ${p.transactions}\nPhotos: ${p.photos}\n\n" +
                        "Replace deletes everything currently in Tel first. Merge adds only records you don't already have.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        val u = Uri.parse(restoreUri); preview = null
                        perform("Backup merged.") { container.backup.restore({ context.contentResolver.openInputStream(u)!! }, RestoreMode.MERGE, container.repository) }
                    }) { Text("Merge") }
                    TextButton(onClick = {
                        val u = Uri.parse(restoreUri); preview = null
                        perform("Backup restored.") { container.backup.restore({ context.contentResolver.openInputStream(u)!! }, RestoreMode.REPLACE, container.repository) }
                    }) { Text("Replace all data", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false; wipeText = "" },
            title = { Text("Delete all data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This permanently deletes every vehicle, refuelling, photo and setting. Make a backup first if you might need them. Type DELETE to confirm.")
                    OutlinedTextField(wipeText, { wipeText = it }, singleLine = true, label = { Text("Type DELETE") })
                }
            },
            confirmButton = {
                TextButton(enabled = wipeText.trim() == "DELETE", onClick = {
                    confirmWipe = false; wipeText = ""
                    perform("All data deleted.") { container.repository.deleteAll() }
                }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false; wipeText = "" }) { Text("Cancel") } },
        )
    }
}

