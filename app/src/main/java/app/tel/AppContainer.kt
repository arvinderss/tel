package app.tel

import android.content.Context
import app.tel.data.BackupManager
import app.tel.data.FuelRepository
import app.tel.data.ImageStore
import app.tel.data.SettingsRepository
import app.tel.data.db.TelDatabase
import app.tel.domain.calc.CalculationEngine

/** Manual dependency injection: one place that builds the app's object graph. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val database: TelDatabase by lazy { TelDatabase.create(app) }
    val imageStore: ImageStore by lazy { ImageStore(app) }
    val engine = CalculationEngine()
    val repository: FuelRepository by lazy { FuelRepository(database, imageStore, engine) }
    val settings: SettingsRepository by lazy { SettingsRepository(database.settings()) }
    val backup: BackupManager by lazy { BackupManager(database, imageStore, BuildConfig.VERSION_NAME) }
}
