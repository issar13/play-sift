package app.playsift.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The screens a Library chip can show, in chip order. */
internal val libraryChipScreens = listOf(Screen.MostPlayed, Screen.NeverPlayed, Screen.Delete, Screen.Large)

internal fun libraryChipLabel(screen: Screen, counts: LibraryCounts?): String {
    val (name, count) = when (screen) {
        Screen.MostPlayed -> "Most played" to counts?.mostPlayed
        Screen.NeverPlayed -> "Never played" to counts?.neverPlayed
        Screen.Delete -> "Delete" to counts?.deleteCandidates
        else -> "Large" to counts?.large
    }
    return if (count == null) name else "$name $count"
}

@Composable
fun LibraryChips(selected: Screen, counts: LibraryCounts?, onSelect: (Screen) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        libraryChipScreens.forEach { screen ->
            FilterChip(
                selected = screen == selected,
                onClick = { onSelect(screen) },
                label = { Text(libraryChipLabel(screen, counts)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}
