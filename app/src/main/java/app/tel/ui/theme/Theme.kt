package app.tel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.tel.domain.model.ThemeMode

// A single calm accent (deep teal) on neutral surfaces; amber only for "needs review".
private val Light = lightColorScheme(
    primary = Color(0xFF0F5C4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBE1),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF4A6359),
    secondaryContainer = Color(0xFFE2EBE6),
    onSecondaryContainer = Color(0xFF16201C),
    tertiary = Color(0xFF8A5100),
    tertiaryContainer = Color(0xFFFFE0B8),
    onTertiaryContainer = Color(0xFF2C1700),
    background = Color(0xFFF7F7F5),
    surface = Color(0xFFF7F7F5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEFF1EF),
    surfaceContainerHigh = Color(0xFFE8EBE9),
    onSurface = Color(0xFF181C1B),
    onSurfaceVariant = Color(0xFF424946),
    outline = Color(0xFF727976),
    outlineVariant = Color(0xFFC2C8C5),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD5BE),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0F5C4A),
    onPrimaryContainer = Color(0xFFCDEBE1),
    secondary = Color(0xFFB1CCC1),
    secondaryContainer = Color(0xFF33443E),
    onSecondaryContainer = Color(0xFFD5E8DF),
    tertiary = Color(0xFFFFB960),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFE0B8),
    background = Color(0xFF121413),
    surface = Color(0xFF121413),
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF1A1D1C),
    surfaceContainer = Color(0xFF1E2120),
    surfaceContainerHigh = Color(0xFF282B2A),
    onSurface = Color(0xFFE1E3E1),
    onSurfaceVariant = Color(0xFFC2C8C5),
    outline = Color(0xFF8C9390),
    outlineVariant = Color(0xFF424946),
    error = Color(0xFFFFB4AB),
)

private val base = Typography()
private val TelTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp),
)

/** Large figures on the dashboard use tabular digits so columns of numbers line up. */
val FigureStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun TelTheme(mode: ThemeMode?, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = TelTypography, content = content)
}
