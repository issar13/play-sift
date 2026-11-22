package app.playsift.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.playsift.player.LibraryList
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun LargeSongsScreen(
    state: LibraryUiState,
    onToggleSelected: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onMoveToTrash: () -> Unit,
    isPreparingTrash: Boolean = false,
    onSendToSpotify: () -> Unit = {},
) {
    val selectedSizeBytes = state.selectedIds.sumOf { selectedId -> state.sizesById[selectedId] ?: 0L }
    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onSelectAll, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Select all") }
        if (state.sizesUnavailable) {
            Text("Sizes unavailable. Couldn't read your file sizes.", modifier = Modifier.padding(16.dp))
        } else if (state.tracks.isEmpty()) {
            Text("No songs of 10 MB or more.", modifier = Modifier.padding(16.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.tracks, key = { track -> track.id }) { track ->
                TrackRow(
                    track = track,
                    list = LibraryList.Large,
                    today = LocalDate.now(ZoneOffset.UTC),
                    isSelected = track.id in state.selectedIds,
                    onToggleSelected = { onToggleSelected(track.id) },
                    sizeBytes = state.sizesById[track.id],
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
            Column {
                SendToSpotifyButton(selectedCount = state.selectedIds.size, isPrimaryAction = false, onClick = onSendToSpotify)
                Button(
                    onClick = onMoveToTrash,
                    enabled = state.selectedIds.isNotEmpty() && !isPreparingTrash,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    if (isPreparingTrash) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Move ${state.selectedIds.size} to trash (${storageText(selectedSizeBytes)})")
                    }
                }
            }
        }
    }
}
