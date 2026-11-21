package app.playsift.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Violet is the one brand colour, everything else is neutral, and error red is kept for deleting. */
private val lightScheme = lightColorScheme(
    primary = Color(0xFF534AB7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEEDFE),
    onPrimaryContainer = Color(0xFF3C3489),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1C1C1A),
    surface = Color(0xFFFAFAF8),
    onSurface = Color(0xFF1C1C1A),
    surfaceVariant = Color(0xFFEDEDEA),
    onSurfaceVariant = Color(0xFF5B5B57),
    secondaryContainer = Color(0xFFEDEDEA),
    onSecondaryContainer = Color(0xFF1C1C1A),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFFAFA9EC),
    onPrimary = Color(0xFF26215C),
    primaryContainer = Color(0xFF3C3489),
    onPrimaryContainer = Color(0xFFEEEDFE),
    background = Color(0xFF131312),
    onBackground = Color(0xFFE6E6E3),
    surface = Color(0xFF131312),
    onSurface = Color(0xFFE6E6E3),
    surfaceVariant = Color(0xFF2A2A28),
    onSurfaceVariant = Color(0xFFB9B9B5),
    secondaryContainer = Color(0xFF2A2A28),
    onSecondaryContainer = Color(0xFFE6E6E3),
)

@Composable
fun PlaySiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkScheme else lightScheme, content = content)
}
