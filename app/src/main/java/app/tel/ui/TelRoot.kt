package app.tel.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.tel.AppContainer
import app.tel.data.AppSettings
import app.tel.ui.dashboard.DashboardScreen
import app.tel.ui.detail.DetailScreen
import app.tel.ui.history.HistoryScreen
import app.tel.ui.onboarding.OnboardingScreen
import app.tel.ui.refuel.RefuelScreen
import app.tel.ui.settings.SettingsScreen
import app.tel.ui.vehicles.VehicleEditScreen
import app.tel.ui.vehicles.VehicleListScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val REFUEL = "refuel?eventId={eventId}"
    const val HISTORY = "history"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings"
    const val VEHICLES = "vehicles"
    const val VEHICLE = "vehicle?vehicleId={vehicleId}"

    fun refuel(eventId: String? = null) = "refuel?eventId=${eventId ?: ""}"
    fun detail(id: String) = "detail/$id"
    fun vehicle(id: String? = null) = "vehicle?vehicleId=${id ?: ""}"
}

private data class TopDest(val route: String, val navRoute: String, val label: String, val icon: ImageVector)

private val topDestinations = listOf(
    TopDest(Routes.DASHBOARD, Routes.DASHBOARD, "Dashboard", Icons.Filled.Home),
    TopDest(Routes.REFUEL, Routes.refuel(), "Refuel", Icons.Filled.Add),
    TopDest(Routes.HISTORY, Routes.HISTORY, "History", Icons.AutoMirrored.Filled.List),
    TopDest(Routes.SETTINGS, Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun TelRoot(container: AppContainer, settings: AppSettings?) {
    when {
        settings == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        !settings.onboarded -> OnboardingScreen()
        else -> MainShell(settings)
    }
}

@Composable
private fun MainShell(settings: AppSettings) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    fun go(d: TopDest) {
        nav.navigate(d.navRoute) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = d.route != Routes.REFUEL }
            launchSingleTop = true
            restoreState = d.route != Routes.REFUEL
        }
    }

    if (wide) {
        // Tablets, foldables, Chromebooks and desktop windows: side rail + content.
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                topDestinations.forEach { d ->
                    NavigationRailItem(
                        selected = currentRoute == d.route,
                        onClick = { go(d) },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(d.label) },
                    )
                }
            }
            Box(Modifier.weight(1f)) { TelNavHost(nav, settings, Modifier) }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    topDestinations.forEach { d ->
                        NavigationBarItem(
                            selected = currentRoute == d.route,
                            onClick = { go(d) },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(d.label) },
                        )
                    }
                }
            },
        ) { padding -> TelNavHost(nav, settings, Modifier.padding(bottom = padding.calculateBottomPadding())) }
    }
}

@Composable
private fun TelNavHost(nav: NavHostController, settings: AppSettings, modifier: Modifier) {
    NavHost(nav, startDestination = Routes.DASHBOARD, modifier = modifier) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                settings = settings,
                onAddRefuel = { nav.navigate(Routes.refuel()) },
                onOpenEvent = { nav.navigate(Routes.detail(it)) },
            )
        }
        composable(
            Routes.REFUEL,
            arguments = listOf(navArgument("eventId") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val eventId = entry.arguments?.getString("eventId")?.takeIf { it.isNotEmpty() }
            RefuelScreen(
                eventId = eventId,
                onDone = {
                    nav.navigate(Routes.DASHBOARD) {
                        popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onCancel = { if (!nav.popBackStack()) nav.navigate(Routes.DASHBOARD) },
                onAddVehicle = { nav.navigate(Routes.vehicle()) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(settings = settings, onOpenEvent = { nav.navigate(Routes.detail(it)) }, onAddRefuel = { nav.navigate(Routes.refuel()) })
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            DetailScreen(
                eventId = id,
                settings = settings,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.refuel(id)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(settings = settings, onManageVehicles = { nav.navigate(Routes.VEHICLES) })
        }
        composable(Routes.VEHICLES) {
            VehicleListScreen(
                settings = settings,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.vehicle(it)) },
                onAdd = { nav.navigate(Routes.vehicle()) },
            )
        }
        composable(Routes.VEHICLE, arguments = listOf(navArgument("vehicleId") { type = NavType.StringType; defaultValue = "" })) { entry ->
            VehicleEditScreen(
                vehicleId = entry.arguments?.getString("vehicleId")?.takeIf { it.isNotEmpty() },
                settings = settings,
                onDone = { nav.popBackStack() },
            )
        }
    }
}
