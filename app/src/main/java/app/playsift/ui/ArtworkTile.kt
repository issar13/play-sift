package app.playsift.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Muted tones so a long list stays balanced; every one keeps the light tile text above 4.5:1. */
internal val artworkPalette = listOf(
    Color(0xFF3D4A66),
    Color(0xFF2F5A57),
    Color(0xFF55583A),
    Color(0xFF5A3A5A),
    Color(0xFF6B4038),
    Color(0xFF66522F),
    Color(0xFF3F5566),
    Color(0xFF3F5C3F),
)

/** The first letter of the first two words, e.g. "Blue Monday" gives "BM"; "?" when there is no letter. */
internal fun artworkInitials(label: String): String =
    label.split(Regex("\\s+"))
        .mapNotNull { word -> word.firstOrNull(Char::isLetterOrDigit) }
        .take(2)
        .joinToString("")
        .uppercase()
        .ifEmpty { "?" }

/** Same artist, same colour, on every screen and every launch. */
internal fun artworkColor(colorSeed: String): Color =
    artworkPalette[Math.floorMod(colorSeed.trim().lowercase().hashCode(), artworkPalette.size)]

/** The initials are decoration; the title and artist beside the tile already name the song. */
@Composable
fun ArtworkTile(label: String, colorSeed: String, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).background(artworkColor(colorSeed), RoundedCornerShape(12.dp)).testTag("artworkTile"),
    ) {
        Text(
            text = artworkInitials(label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { hideFromAccessibility() },
        )
    }
}
