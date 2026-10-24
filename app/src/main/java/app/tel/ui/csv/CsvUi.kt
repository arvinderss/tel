package app.tel.ui.csv

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.tel.data.Restorable
import app.tel.data.RestoreMode
import app.tel.data.TransferException
import app.tel.ui.appContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val SUGGESTED_FILE_NAME = "tel-fillups.csv"

/** Handles for starting the CSV flows from any screen. Dialogs are rendered by [rememberCsvActions]. */
class CsvActions(
    /** Explains the CSV copy, then opens the system "save as" dialog. */
    val setUp: () -> Unit,
    /** Opens the system file picker to restore from (and keep using) an existing Tel CSV. */
    val useExisting: () -> Unit,
    /** Opens the file picker to restore from a CSV or old .zip backup, without linking it. */
    val restoreOnly: () -> Unit,
)

private data class PendingRestore(val uri: Uri, val data: Restorable, val link: Boolean, val fileName: String?)

/**
 * @param onMessage short result text for a snackbar.
 * @param onRestored called after a successful restore (e.g. to finish first-time setup).
 */
@Composable
fun rememberCsvActions(onMessage: (String) -> Unit, onRestored: suspend () -> Unit = {}): CsvActions {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var explain by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingRestore?>(null) }
    var hasData by remember { mutableStateOf(false) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = c.csvCopy.linkNewFile(uri)
            onMessage(if (ok) "CSV copy set up. Tel will keep it up to date." else "Couldn't write to that file. Try another location.")
        }
    }

    fun picked(uri: Uri, link: Boolean) {
        scope.launch {
            try {
                val data = c.csvCopy.read(uri)
                hasData = c.repository.events.first().isNotEmpty() || c.repository.vehicles.first().isNotEmpty()
                pending = PendingRestore(uri, data, link, c.csvCopy.displayName(uri))
            } catch (e: TransferException) {
                onMessage(e.message ?: "That file couldn't be read.")
            }
        }
    }

    val types = arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/zip", "application/octet-stream", "*/*")
    val openLinked = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) picked(uri, link = true) }
    val openOnce = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) picked(uri, link = false) }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Keep a copy as a CSV file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tel will save all your vehicles and fill-ups to one CSV file and update it after every change. It opens in any spreadsheet app.")
                    Text("The file stays on your phone even if you uninstall Tel. To get your data back, open it from Tel's welcome screen.")
                    Text(
                        "Tip: in the next screen, create a folder just for it, such as Documents/Tel, and save the file there.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary),
                    )
                    Text("Tel can only see this one file, nothing else on your phone.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { explain = false; create.launch(SUGGESTED_FILE_NAME) }) { Text("Choose where to save") } },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Cancel") } },
        )
    }

    pending?.let { p ->
        val counts = "${p.data.vehicles.size} vehicle(s) and ${p.data.events.size} fill-up(s)"
        val skipped = if (p.data.skipped.isNotEmpty()) "\n\n${p.data.skipped.size} row(s) couldn't be read and will be left out:\n" + p.data.skipped.take(3).joinToString("\n") else ""
        val linkNote = if (p.link) "\n\nAfterwards Tel keeps this file up to date." else ""
        fun run(mode: RestoreMode) {
            pending = null
            scope.launch {
                runCatching {
                    if (p.link) c.csvCopy.restoreAndLink(p.uri, p.data, mode) else c.transfer.restore(p.data, mode)
                    onRestored()
                }.onSuccess { onMessage("Restored $counts.") }
                    .onFailure { onMessage("Restore failed. Nothing was changed.") }
            }
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore from ${p.fileName ?: "this file"}?") },
            text = {
                Text(
                    "Found $counts in this ${p.data.kind}." +
                        (if (hasData) "\n\nReplace removes the vehicles and fill-ups currently in Tel first. Merge adds only the ones Tel doesn't have yet." else "") +
                        skipped + linkNote,
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (hasData) {
                        TextButton(onClick = { run(RestoreMode.MERGE) }) { Text("Merge") }
                        TextButton(onClick = { run(RestoreMode.REPLACE) }) { Text("Replace current data", color = MaterialTheme.colorScheme.error) }
                    } else {
                        TextButton(onClick = { run(RestoreMode.REPLACE) }) { Text("Restore") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    return remember {
        CsvActions(
            setUp = { explain = true },
            useExisting = { openLinked.launch(types) },
            restoreOnly = { openOnce.launch(types) },
        )
    }
}
