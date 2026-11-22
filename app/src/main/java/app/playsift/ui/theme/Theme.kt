package app.playsift.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Near-black surfaces with violet as the one brand colour; error red is kept for deleting. Dark is the only theme for now. */
internal val playSiftColorScheme = darkColorScheme(
    primary = Color(0xFF8B7FF0),
    onPrimary = Color(0xFF0E0D12),
    primaryContainer = Color(0xFF2B2650),
    onPrimaryContainer = Color(0xFFE6E2FF),
    secondary = Color(0xFF8B7FF0),
    onSecondary = Color(0xFF0E0D12),
    secondaryContainer = Color(0xFF2B2650),
    onSecondaryContainer = Color(0xFFE6E2FF),
    tertiary = Color(0xFF8B7FF0),
    onTertiary = Color(0xFF0E0D12),
    background = Color(0xFF0E0D12),
    onBackground = Color(0xFFEDEBF5),
    surface = Color(0xFF17161D),
    onSurface = Color(0xFFEDEBF5),
    surfaceVariant = Color(0xFF201F28),
    onSurfaceVariant = Color(0xFFA09DB0),
    surfaceContainerLowest = Color(0xFF0E0D12),
    surfaceContainerLow = Color(0xFF131218),
    surfaceContainer = Color(0xFF17161D),
    surfaceContainerHigh = Color(0xFF201F28),
    surfaceContainerHighest = Color(0xFF282732),
    outline = Color(0xFF6F6C80),
    outlineVariant = Color(0xFF36343F),
    error = Color(0xFFF2766B),
    onError = Color(0xFF0E0D12),
    errorContainer = Color(0xFF3A1714),
    onErrorContainer = Color(0xFFFBD9D5),
)

private val playSiftShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun PlaySiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = playSiftColorScheme, shapes = playSiftShapes, content = content)
}
