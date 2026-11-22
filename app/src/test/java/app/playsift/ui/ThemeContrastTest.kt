package app.playsift.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.playsift.ui.theme.playSiftColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private val minimumTextContrast = 4.5

    private fun contrast(foreground: Color, background: Color): Double {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertReadable(name: String, foreground: Color, background: Color) {
        val ratio = contrast(foreground, background)
        assertTrue("$name contrast was $ratio", ratio >= minimumTextContrast)
    }

    @Test
    fun textMeetsContrastOnEverySurface() {
        val scheme = playSiftColorScheme
        listOf(scheme.background, scheme.surface, scheme.surfaceVariant, scheme.surfaceContainer, scheme.surfaceContainerHigh).forEach { surface ->
            assertReadable("onSurface on $surface", scheme.onSurface, surface)
            assertReadable("onSurfaceVariant on $surface", scheme.onSurfaceVariant, surface)
            assertReadable("primary on $surface", scheme.primary, surface)
            assertReadable("error on $surface", scheme.error, surface)
        }
    }

    @Test
    fun filledAndContainerPairsMeetContrast() {
        val scheme = playSiftColorScheme
        assertReadable("onPrimary on primary", scheme.onPrimary, scheme.primary)
        assertReadable("onError on error", scheme.onError, scheme.error)
        assertReadable("onPrimaryContainer on primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
        assertReadable("onSecondaryContainer on secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer)
        assertReadable("onErrorContainer on errorContainer", scheme.onErrorContainer, scheme.errorContainer)
    }

    @Test
    fun artworkInitialsReadOnEveryTile() {
        artworkPalette.forEach { tile -> assertReadable("onSurface on tile $tile", playSiftColorScheme.onSurface, tile) }
    }
}
