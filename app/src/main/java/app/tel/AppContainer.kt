package app.tel

import android.content.Context
import app.tel.data.CsvCopy
import app.tel.data.DataTransfer
import app.tel.data.FuelRepository
import app.tel.data.SettingsRepository
import app.tel.data.db.TelDatabase
import app.tel.domain.calc.CalculationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency injection: one place that builds the app's object graph. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    /** Lives as long as the process; used for the background CSV copy. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: TelDatabase by lazy { TelDatabase.create(app) }
    val engine = CalculationEngine()
    val repository: FuelRepository by lazy { FuelRepository(database, engine) }
    val settings: SettingsRepository by lazy { SettingsRepository(database.settings()) }
    val transfer: DataTransfer by lazy { DataTransfer(repository) }
    val csvCopy: CsvCopy by lazy { CsvCopy(app, repository, settings, transfer, appScope) }

    /** Photos were removed in 1.1; delete any left from earlier versions. */
    fun removeLegacyFiles() {
        appScope.launch {
            File(app.filesDir, "data").deleteRecursively()
            File(app.cacheDir, "drafts").deleteRecursively()
            File(app.cacheDir, "camera").deleteRecursively()
        }
    }
}
