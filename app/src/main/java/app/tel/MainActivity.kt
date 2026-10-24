package app.tel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tel.data.AppSettings
import app.tel.ui.TelRoot
import app.tel.ui.theme.TelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TelApp).container
        // Refresh stored derived values (e.g. after a database upgrade). Cheap: a few hundred rows at most.
        if (savedInstanceState == null) lifecycleScope.launch { container.repository.recomputeAll() }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
            TelTheme(mode = settings?.theme) {
                TelRoot(container, settings)
            }
        }
    }
}
