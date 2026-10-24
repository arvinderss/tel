package app.tel

import android.app.Application

class TelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.removeLegacyFiles()
        container.csvCopy.start()
    }
}
