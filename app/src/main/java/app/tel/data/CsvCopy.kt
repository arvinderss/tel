package app.tel.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps ONE user-chosen CSV file (e.g. Documents/Tel/tel-fillups.csv) up to date with all data.
 *
 * The file is chosen in the system file dialog (create or open document). That grants access
 * to that single file only, survives app updates and reboots, and needs no storage permission.
 * The file itself is the user's and stays when Tel is uninstalled; after reinstalling, opening it
 * again restores everything.
 *
 * The app's database stays the working copy; the CSV is a copy rewritten after every change.
 */
class CsvCopy(
    private val context: Context,
    private val repository: FuelRepository,
    private val settings: SettingsRepository,
    private val transfer: DataTransfer,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val resolver get() = context.contentResolver

    /** Starts rewriting the file whenever vehicles or fill-ups change. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(
                repository.vehicles,
                repository.events,
                settings.settings.map { it.csv.uri }.distinctUntilChanged(),
            ) { vehicles, events, uri -> (vehicles.isEmpty() && events.isEmpty()) to uri }
                .debounce(400)
                .collect { (empty, uri) ->
                    // Never blank out an existing file automatically (e.g. right after "Delete all data").
                    if (uri != null && !empty) writeNow()
                }
        }
    }

    /** Writes the current data to the linked file. Returns false (and records why) on failure. */
    suspend fun writeNow(): Boolean = mutex.withLock {
        val uri = settings.current().csv.uri?.let(Uri::parse) ?: return@withLock false
        val bytes = transfer.csvText().toByteArray(Charsets.UTF_8)
        val ok = withContext(Dispatchers.IO) {
            val modes = listOf("wt", "rwt", "w")
            modes.any { mode ->
                runCatching {
                    resolver.openOutputStream(uri, mode)?.use { it.write(bytes); it.flush() } ?: error("no stream")
                }.isSuccess
            }
        }
        if (ok) settings.setCsvWritten(clock())
        else settings.setCsvError("Couldn't update your CSV file. It may have been moved, renamed or deleted.")
        ok
    }

    /** Links a file the user just created and writes to it straight away. */
    suspend fun linkNewFile(uri: Uri): Boolean {
        persist(uri)
        settings.setCsvFile(uri.toString(), displayName(uri))
        return writeNow()
    }

    /** Reads a file the user picked (an existing Tel CSV). Throws [TransferException] if it isn't one. */
    suspend fun read(uri: Uri): Restorable = withContext(Dispatchers.IO) {
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: throw TransferException("Couldn't open that file.")
        transfer.read(bytes)
    }

    /** Restores from an existing Tel CSV, then keeps that same file up to date. */
    suspend fun restoreAndLink(uri: Uri, data: Restorable, mode: RestoreMode): Boolean {
        transfer.restore(data, mode)
        persist(uri)
        settings.setCsvFile(uri.toString(), displayName(uri))
        return writeNow()
    }

    /** Stops updating the file. The file itself is left as it is. */
    suspend fun unlink() {
        settings.current().csv.uri?.let { u ->
            runCatching {
                resolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        settings.setCsvFile(null, null)
    }

    private fun persist(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
