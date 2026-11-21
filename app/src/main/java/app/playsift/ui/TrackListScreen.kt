package app.playsift.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.playsift.player.HistoryHealth
import app.playsift.player.LibraryList
import app.playsift.player.PlayerTrack
import java.time.LocalDate

@Composable
fun TrackListScreen(
    state: LibraryUiState,
    today: LocalDate,
    onToggleSelected: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onMoveToTrash: () -> Unit,
    isPreparingTrash: Boolean = false,
) {
    val isDeleteList = state.list == LibraryList.DeleteCandidates
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.list != LibraryList.MostPlayed) {
            HistoryBanner(health = state.historyHealth, cutoffLabel = state.cutoff.label)
        }
        if (isDeleteList) {
            TextButton(onClick = onSelectAll, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Select all") }
        }
        if (state.list == LibraryList.MostPlayed) {
            MostPlayedList(tracks = state.tracks, today = today, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.tracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        list = state.list,
                        today = today,
                        isSelected = track.id in state.selectedIds,
                        onToggleSelected = { onToggleSelected(track.id) },
                    )
                }
            }
        }
        if (isDeleteList) {
            Button(
                onClick = onMoveToTrash,
                enabled = state.selectedIds.isNotEmpty() && !isPreparingTrash,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                if (isPreparingTrash) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Move ${state.selectedIds.size} to trash")
                }
            }
        }
    }
}

@Composable
private fun MostPlayedList(tracks: List<PlayerTrack>, today: LocalDate, modifier: Modifier) {
    val entries = groupByMatchingPlays(tracks)
    var expandedPlays by rememberSaveable { mutableStateOf(listOf<Int>()) }
    LazyColumn(modifier = modifier) {
        entries.forEach { entry ->
            when (entry) {
                is MostPlayedEntry.Single -> item(key = entry.track.id) {
                    TrackRow(entry.track, LibraryList.MostPlayed, today, isSelected = false, onToggleSelected = {})
                }
                is MostPlayedEntry.Group -> {
                    val isExpanded = entry.plays in expandedPlays
                    val toggleGroup = {
                        expandedPlays = if (isExpanded) expandedPlays - entry.plays else expandedPlays + entry.plays
                    }
                    val visibleTracks = if (isExpanded) entry.tracks else entry.tracks.take(GROUP_COLLAPSED_SONGS)
                    item(key = "group-${entry.plays}") {
                        Text(
                            text = "${if (entry.plays == 1) "1 play" else "${entry.plays} plays"} \u00b7 ${entry.tracks.size} songs",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = toggleGroup).padding(16.dp),
                        )
                    }
                    items(visibleTracks, key = { track -> track.id }) { track ->
                        TrackRow(track, LibraryList.MostPlayed, today, isSelected = false, onToggleSelected = {})
                    }
                    if (!isExpanded) {
                        item(key = "more-${entry.plays}") {
                            Text(
                                text = "Show ${entry.tracks.size - GROUP_COLLAPSED_SONGS} more",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable(onClick = toggleGroup).padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val GROUP_COLLAPSED_SONGS = 2

@Composable
private fun HistoryBanner(health: HistoryHealth, cutoffLabel: String) {
    val message = when (health) {
        HistoryHealth.Healthy -> return
        HistoryHealth.TooYoung ->
            "Your music player has less than $cutoffLabel of history, so some of these may just be new."
        HistoryHealth.MostlyUnplayed ->
            "Almost nothing has a play count. If you reset your player's library, play counts started over."
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text = message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun TrackRow(
    track: PlayerTrack,
    list: LibraryList,
    today: LocalDate,
    isSelected: Boolean,
    onToggleSelected: () -> Unit,
    sizeBytes: Long? = null,
) {
    val isSelectable = list == LibraryList.DeleteCandidates || list == LibraryList.Large
    val rowModifier = if (isSelectable) {
        Modifier.toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggleSelected() })
    } else {
        Modifier
    }
    Row(
        modifier = rowModifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isSelectable) {
            Checkbox(checked = isSelected, onCheckedChange = null)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(text = track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Text(text = rowTrailingText(track, list, today, sizeBytes), style = MaterialTheme.typography.bodySmall)
    }
}

private fun rowTrailingText(track: PlayerTrack, list: LibraryList, today: LocalDate, sizeBytes: Long?) =
    when (list) {
        LibraryList.Large -> sizeBytes?.let(::megabytesText).orEmpty()
        LibraryList.MostPlayed -> if (track.playCount == 1) "1 play" else "${track.playCount} plays"
        else -> addedLabel(track.firstSeenAt, today)
    }
