package app.playsift.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import app.playsift.spotify.PickerState
import app.playsift.spotify.SpotifyTrack
import app.playsift.spotify.lengthHint
import app.playsift.spotify.lengthText
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import app.playsift.player.PlayerTrack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.playsift.spotify.OfferedSong
import app.playsift.spotify.SourceChoice
import app.playsift.spotify.SpotifyStage
import app.playsift.spotify.SpotifyUiState
import app.playsift.spotify.UnsureSong

class SpotifyActions(
    val onSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onClientIdChange: (String) -> Unit,
    val onChooseSource: (SourceChoice) -> Unit,
    val onTopCountTextChange: (String) -> Unit,
    val onUseAllPlayedCount: () -> Unit,
    val onPlaylistNameChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onCancelSend: () -> Unit,
    val onConfirmUnsure: () -> Unit,
    val onSkipUnsure: () -> Unit,
    val onOpenPlaylist: (String) -> Unit,
    val onToggleOffered: (Long) -> Unit,
    val onSelectAllOffered: () -> Unit,
    val onMoveToTrash: (List<OfferedSong>) -> Unit,
    val onBackToSetup: () -> Unit,
    val onSearchOnSpotify: (PlayerTrack) -> Unit,
    val onShareNotFound: (List<PlayerTrack>) -> Unit,
    val onOpenPicker: (PlayerTrack) -> Unit,
    val onPickOption: (SpotifyTrack) -> Unit,
    val onClosePicker: () -> Unit,
)

@Composable
fun SpotifyScreen(state: SpotifyUiState, isPreparingTrash: Boolean, actions: SpotifyActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Send to Spotify", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.confirmation?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (!state.isSignedIn) {
                SignInSection(state, actions)
            } else {
                StageSection(state, isPreparingTrash, actions)
            }
        }
    }
    state.picker?.let { picker -> PickerSheet(picker, actions) }
}

@Composable
private fun SignInSection(state: SpotifyUiState, actions: SpotifyActions) {
    Text("Make Spotify playlists from your most played songs, everything you have played or songs you pick. Play Sift never sees your Spotify password.")
    if (!state.hasClientId) {
        Text("Add your Spotify app's Client ID to continue. Make a free app at developer.spotify.com and register the redirect address playsift://spotify-callback.")
    }
    ClientIdField(state, actions)
    Button(onClick = actions.onSignIn, enabled = state.hasClientId) { Text("Sign in to Spotify") }
}

@Composable
private fun ClientIdField(state: SpotifyUiState, actions: SpotifyActions) {
    OutlinedTextField(
        value = state.clientIdOverride,
        onValueChange = actions.onClientIdChange,
        label = { Text(if (state.builtInClientIdMissing) "Spotify Client ID" else "Client ID (leave empty for the built-in one)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SpotifyAccountSection(state: SpotifyUiState, actions: SpotifyActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = if (state.isSignedIn) state.userName?.let { "Signed in as $it" } ?: "Signed in to Spotify" else "Not signed in to Spotify",
            style = MaterialTheme.typography.bodySmall,
        )
        ClientIdField(state, actions)
        if (state.isSignedIn) OutlinedButton(onClick = actions.onSignOut) { Text("Sign out") }
    }
}

@Composable
private fun StageSection(state: SpotifyUiState, isPreparingTrash: Boolean, actions: SpotifyActions) {
    when (val stage = state.stage) {
        SpotifyStage.Setup -> SetupSection(state, actions)
        is SpotifyStage.Matching -> {
            Text(if (stage.total == 0) "Reading your songs..." else "Matching songs on Spotify: ${stage.done} of ${stage.total}")
            LinearProgressIndicator(
                progress = { if (stage.total == 0) 0f else stage.done.toFloat() / stage.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("This keeps going in the background. You can close the app.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = actions.onCancelSend) { Text("Cancel") }
        }
        SpotifyStage.Publishing -> {
            CircularProgressIndicator()
            Text("Making the playlist...")
        }
        is SpotifyStage.Done -> DoneSection(stage, isPreparingTrash, actions)
        is SpotifyStage.Failed -> {
            Text(stage.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions.onBackToSetup) { Text("Back") }
        }
    }
}

@Composable
private fun SetupSection(state: SpotifyUiState, actions: SpotifyActions) {
    Text("Songs to send", style = MaterialTheme.typography.titleMedium)
    SourceRadio("Top played", state.choice == SourceChoice.TopPlayed, true) { actions.onChooseSource(SourceChoice.TopPlayed) }
    if (state.choice == SourceChoice.TopPlayed) TopCountInput(state, actions)
    SourceRadio("Everything played", state.choice == SourceChoice.EverythingPlayed, true) { actions.onChooseSource(SourceChoice.EverythingPlayed) }
    SourceRadio(
        label = "Songs you ticked (${state.tickedTracks.size})",
        isSelected = state.choice == SourceChoice.TickedSongs,
        isEnabled = state.tickedTracks.isNotEmpty(),
    ) { actions.onChooseSource(SourceChoice.TickedSongs) }
    OutlinedTextField(
        value = state.playlistName,
        onValueChange = actions.onPlaylistNameChange,
        label = { Text("Playlist name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Playlists are private. Sending the same source again updates its playlist instead of making a new one.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = actions.onSend, enabled = state.canSend) {
        Text(state.topCount?.takeIf { state.choice == SourceChoice.TopPlayed }?.let { "Send $it songs to Spotify" } ?: "Create playlist")
    }
}

private val TOP_COUNT_PRESETS = listOf(5, 25, 100, 250)

@Composable
private fun TopCountInput(state: SpotifyUiState, actions: SpotifyActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TopCountField(state.topCountText, actions.onTopCountTextChange)
        if (state.topCount == null) Text("Enter a number above 0 to send.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TOP_COUNT_PRESETS.forEach { preset ->
                FilterChip(
                    selected = state.topCount == preset,
                    onClick = { actions.onTopCountTextChange(preset.toString()) },
                    label = { Text(preset.toString()) },
                )
            }
            FilterChip(selected = false, onClick = actions.onUseAllPlayedCount, label = { Text("All") })
        }
    }
}

/** Holds the typed text as it is, so it can be emptied and retyped; focusing selects it all so typing replaces it. */
@Composable
private fun TopCountField(text: String, onTextChange: (String) -> Unit) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(text)) }
    if (fieldValue.text != text) fieldValue = TextFieldValue(text, TextRange(text.length))
    OutlinedTextField(
        value = fieldValue,
        onValueChange = { changed ->
            fieldValue = changed
            onTextChange(changed.text)
        },
        label = { Text("How many songs") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        singleLine = true,
        modifier = Modifier.testTag("topCountField").onFocusChanged { focus ->
            if (focus.isFocused) fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
        },
    )
}

@Composable
private fun SourceRadio(label: String, isSelected: Boolean, isEnabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = isSelected, enabled = isEnabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null, enabled = isEnabled)
        Text(text = label, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun UnsureSection(unsure: UnsureSong, remaining: Int, actions: SpotifyActions) {
    Text("Is this the same song? ($remaining left to check)", style = MaterialTheme.typography.titleMedium)
    Text("Yours: ${unsure.track.title} by ${unsure.track.artist}")
    Text("On Spotify: ${unsure.candidate.title} by ${unsure.candidate.artists.joinToString(", ")}")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.onConfirmUnsure) { Text("Add it") }
        OutlinedButton(onClick = actions.onSkipUnsure) { Text("Skip") }
    }
    TextButton(onClick = { actions.onOpenPicker(unsure.track) }) { Text("See more options") }
}

@Composable
private fun DoneSection(done: SpotifyStage.Done, isPreparingTrash: Boolean, actions: SpotifyActions) {
    val result = done.result
    val confirmedCount = result.added.count { it.wasConfirmedByUser }
    Text(if (result.playlist == null) "Songs to check" else "Playlist ready", style = MaterialTheme.typography.titleLarge)
    Text("Matched: ${result.added.size - confirmedCount}")
    Text("Added after you confirmed: $confirmedCount")
    Text("Skipped as unsure: ${result.skippedUnsure.size}")
    if (result.notFound.isEmpty()) Text("Not found: 0") else NotFoundSection(result.notFound, actions)
    result.playlist?.let { playlist -> Button(onClick = { actions.onOpenPlaylist(playlist.url) }) { Text("Open in Spotify") } }
    done.unsure.firstOrNull()?.let { unsure -> UnsureSection(unsure, done.unsure.size, actions) }
    OfferSection(done, isPreparingTrash, actions)
    OutlinedButton(onClick = actions.onBackToSetup) { Text("Done") }
}

/** Songs Spotify did not have: collapsed by default, each with a way to search by hand, and one list to share. Never offered for the trash. */
@Composable
private fun NotFoundSection(notFound: List<PlayerTrack>, actions: SpotifyActions) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { isExpanded = !isExpanded }) {
            Text("Not found (${notFound.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (isExpanded) {
            OutlinedButton(onClick = { actions.onShareNotFound(notFound) }) { Text("Share list") }
            notFound.forEach { track -> NotFoundRow(track, actions) }
        }
    }
}

@Composable
private fun NotFoundRow(track: PlayerTrack, actions: SpotifyActions) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { actions.onOpenPicker(track) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkTile(label = track.title, colorSeed = track.artist)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("Find a match", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Up to eight Spotify tracks for one song; tapping one adds it to the playlist. Closing leaves the song where it was. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(picker: PickerState, actions: SpotifyActions) {
    ModalBottomSheet(onDismissRequest = actions.onClosePicker, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pick the right song", style = MaterialTheme.typography.titleLarge)
            Text("Yours: ${picker.track.title} by ${picker.track.artist} (${lengthText(picker.track.durationMillis)})", style = MaterialTheme.typography.bodyMedium)
            picker.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (picker.isLoading) CircularProgressIndicator()
            if (!picker.isLoading && picker.errorMessage == null && picker.options.isEmpty()) Text("Spotify has nothing close to this song.")
            picker.options.forEach { option -> PickerOption(option, picker.track, actions) }
            OutlinedButton(onClick = actions.onClosePicker, modifier = Modifier.fillMaxWidth()) { Text("None of these") }
            TextButton(onClick = { actions.onSearchOnSpotify(picker.track) }) { Text("Open search in Spotify") }
        }
    }
}

@Composable
private fun PickerOption(option: SpotifyTrack, local: PlayerTrack, actions: SpotifyActions) {
    val hint = lengthHint(local.durationMillis, option.durationMillis)
    Column(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { actions.onPickOption(option) }.padding(vertical = 8.dp)) {
        Text(option.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            listOf(option.artists.joinToString(", "), option.album).filter { it.isNotBlank() }.joinToString(" - "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            listOfNotNull(lengthText(option.durationMillis), hint).joinToString("  "),
            style = MaterialTheme.typography.bodySmall,
            color = if (hint == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun OfferSection(done: SpotifyStage.Done, isPreparingTrash: Boolean, actions: SpotifyActions) {
    if (done.offer.isEmpty()) return
    val selectedSongs = done.offer.filter { it.track.id in done.selectedIds }
    val selectedSizeBytes = selectedSongs.sumOf { it.sizeBytes ?: 0L }
    Text("Free up space (optional)", style = MaterialTheme.typography.titleMedium)
    Text(
        "These songs are now on Spotify. Nothing is selected, and nothing moves until you confirm. They go to Android's trash, where you can restore them for about 30 days.",
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = actions.onSelectAllOffered) { Text("Select all") }
    done.offer.forEach { offered ->
        val isSelected = offered.track.id in done.selectedIds
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { actions.onToggleOffered(offered.track.id) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = isSelected, onCheckedChange = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(offered.track.title, maxLines = 1)
                Text(offered.track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            offered.sizeBytes?.let { sizeBytes -> Text(storageText(sizeBytes), style = MaterialTheme.typography.bodySmall) }
        }
    }
    Button(
        onClick = { actions.onMoveToTrash(selectedSongs) },
        enabled = selectedSongs.isNotEmpty() && !isPreparingTrash,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Move ${selectedSongs.size} songs now on Spotify to trash (${storageText(selectedSizeBytes)})")
    }
}
