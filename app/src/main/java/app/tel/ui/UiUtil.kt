package app.tel.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tel.AppContainer
import app.tel.TelApp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Dates {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    fun date(ms: Long): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(ms).atZone(zone))
    fun dateTime(ms: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(zone))
    fun short(ms: Long): String = DateTimeFormatter.ofPattern("d MMM").format(Instant.ofEpochMilli(ms).atZone(zone))
    fun shortWithYear(ms: Long): String = DateTimeFormatter.ofPattern("d MMM yy").format(Instant.ofEpochMilli(ms).atZone(zone))
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as TelApp).container

/** Builds a ViewModel with access to the app container. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
