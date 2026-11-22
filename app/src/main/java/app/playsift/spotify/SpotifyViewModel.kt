package app.playsift.spotify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playsift.files.FileLocation
import app.playsift.files.FileSizes
import app.playsift.files.fileLocation
import app.playsift.player.PlayerPlaylists
import app.playsift.player.PlayerTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SpotifyViewModel(
    private val session: SpotifySession,
    private val tokenStore: TokenStore,
    private val clientId: SpotifyClientId,
    private val api: SpotifyApi,
    private val matcher: SongMatcher,
    private val publisher: PlaylistPublisher,
    private val playerPlaylists: PlayerPlaylists,
    private val fileSizes: FileSizes,
    private val store: SendStore,
    private val scheduler: SendScheduler,
    private val playedSongCount: suspend () -> Int,
    private val builtInClientId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SpotifyUiState(
            builtInClientIdMissing = builtInClientId.isBlank(),
            clientIdOverride = "",
            isSignedIn = false,
            userName = null,
            choice = SourceChoice.TopPlayed,
            topCountText = DEFAULT_TOP_COUNT.toString(),
            playlistName = SendSource.TopPlayed(DEFAULT_TOP_COUNT).defaultName,
            tickedTracks = emptyList(),
            stage = SpotifyStage.Setup,
        ),
    )
    val state: StateFlow<SpotifyUiState> = mutableState

    private val decisionLock = Mutex()
    private val optionSearch = OptionSearch(api)

    init {
        viewModelScope.launch {
            mutableState.update { it.copy(clientIdOverride = clientId.override()) }
            refreshSignInState()
        }
        refreshPlaylistHint()
        viewModelScope.launch {
            combine(store.saved, scheduler.state) { saved, work -> saved to work }.collect { (saved, work) ->
                val stage = stageFor(saved, work)
                mutableState.update { it.copy(stage = stage) }
                if (stage is SpotifyStage.Failed) refreshSignInState()
            }
        }
    }

    /** Saves a fresh PKCE verifier, then hands the Spotify sign-in page address to [openBrowser]. */
    fun signIn(openBrowser: (String) -> Unit) {
        viewModelScope.launch {
            val effectiveClientId = clientId.effective()
            if (effectiveClientId.isBlank()) return@launch
            val pending = PendingAuthorization(verifier = newCodeVerifier(), state = newAuthorizationState(), clientId = effectiveClientId)
            tokenStore.savePendingAuthorization(pending)
            openBrowser(authorizationUrl(effectiveClientId, codeChallengeFor(pending.verifier), pending.state))
        }
    }

    /** Called with the parts of the `playsift://spotify-callback` address; anything that does not match the pending sign-in is refused. */
    fun onAuthCallback(code: String?, state: String?, error: String?) {
        viewModelScope.launch {
            val pending = tokenStore.pendingAuthorization()
            if (error != null || code == null || pending == null || pending.state != state) {
                showMessage("Spotify sign-in was not completed.")
                return@launch
            }
            try {
                session.completeSignIn(code, pending.verifier, pending.clientId)
                tokenStore.clearPendingAuthorization()
                refreshSignInState()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                showMessage("Spotify sign-in failed. Check the Client ID and try again.")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            session.signOut()
            mutableState.update { it.copy(isSignedIn = false, userName = null, stage = SpotifyStage.Setup) }
        }
    }

    fun setClientIdOverride(text: String) {
        mutableState.update { it.copy(clientIdOverride = text) }
        viewModelScope.launch { clientId.setOverride(text) }
    }

    /** Called by the lists when the user ticks songs and taps Send to Spotify. A running send is left alone. */
    fun useTickedSongs(tracks: List<PlayerTrack>) {
        mutableState.update {
            it.copy(choice = SourceChoice.TickedSongs, tickedTracks = tracks, playlistName = SendSource.TickedSongs.defaultName)
        }
        viewModelScope.launch { store.clearSaved() }
        refreshPlaylistHint()
    }

    fun chooseSource(choice: SourceChoice) {
        mutableState.update {
            val chosen = it.copy(choice = choice)
            chosen.copy(playlistName = chosen.source?.defaultName.orEmpty())
        }
        if (choice == SourceChoice.PlayerPlaylist) loadPlayerPlaylists()
        refreshPlaylistHint()
    }

    /** Picks one of the loaded player playlists. Its name fills the name field unless the user typed a different name. */
    fun choosePlayerPlaylist(playlistId: Long) {
        mutableState.update { current ->
            val picked = current.playerPlaylists.firstOrNull { it.id == playlistId } ?: return@update current
            val hasTypedName = current.playlistName.isNotBlank() && current.playlistName != current.selectedPlayerPlaylist?.name
            current.copy(
                selectedPlayerPlaylistId = picked.id,
                playlistName = if (hasTypedName) current.playlistName else picked.name,
            )
        }
        refreshPlaylistHint()
    }

    private fun loadPlayerPlaylists() {
        mutableState.update { it.copy(isLoadingPlayerPlaylists = true, playerPlaylistsError = null) }
        viewModelScope.launch {
            try {
                val loaded = playerPlaylists.playlists()
                mutableState.update { it.copy(playerPlaylists = loaded, isLoadingPlayerPlaylists = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoadingPlayerPlaylists = false, playerPlaylistsError = PLAYER_PLAYLISTS_FAILED_MESSAGE) }
            }
        }
    }

    fun setTopCountText(input: String) {
        mutableState.update { withTopCountText(it, topCountTextFrom(input)) }
        refreshPlaylistHint()
    }

    /** The "All" chip: the number of songs that have been played, at most [MAX_TOP_COUNT]. */
    fun useAllPlayedCount() {
        viewModelScope.launch {
            val count = try {
                playedSongCount().coerceAtMost(MAX_TOP_COUNT)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                showMessage(PLAYED_COUNT_FAILED_MESSAGE)
                return@launch
            }
            mutableState.update { withTopCountText(it, count.toString()) }
            refreshPlaylistHint()
        }
    }

    private fun withTopCountText(current: SpotifyUiState, text: String): SpotifyUiState {
        val typed = current.copy(topCountText = text)
        val count = typed.topCount
        return if (current.choice == SourceChoice.TopPlayed && count != null) typed.copy(playlistName = SendSource.TopPlayed(count).defaultName) else typed
    }

    fun setPlaylistName(name: String) {
        mutableState.update { it.copy(playlistName = name) }
        refreshPlaylistHint()
    }

    /** Works out whether the current source and name would update a remembered playlist, for the hint under the name field. */
    private fun refreshPlaylistHint() {
        viewModelScope.launch {
            val current = mutableState.value
            val source = current.source
            val willUpdate = source != null && hasPlaylistOrFalse(source, source.nameFor(current.playlistName))
            mutableState.update { it.copy(willUpdatePlaylist = willUpdate) }
        }
    }

    /** The hint is a nicety: while signed out or offline the account is unknown, so it says a new playlist will be made. */
    private suspend fun hasPlaylistOrFalse(source: SendSource, name: String): Boolean =
        try {
            publisher.hasPlaylistFor(source, name)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    /** Forgets the last result (and any unsure songs still waiting) so the setup form shows again, after a decision that is being saved. */
    fun backToSetup() {
        mutableState.update { it.copy(picker = null, confirmation = null) }
        viewModelScope.launch { decisionLock.withLock { store.clearSaved() } }
        refreshPlaylistHint()
    }

    /** Stops the background send; songs matched so far stay cached for the next run. */
    fun cancelSend() = scheduler.cancel()

    /** Hands the send to the background worker; only one send runs at a time. */
    fun send() {
        val current = mutableState.value
        if (!current.canSend) return
        val request = SendRequest(
            choice = current.choice,
            topCount = current.topCount ?: DEFAULT_TOP_COUNT,
            playlistName = current.playlistName,
            tickedTracks = if (current.choice == SourceChoice.TickedSongs) current.tickedTracks else emptyList(),
            playerPlaylistId = current.selectedPlayerPlaylistId.takeIf { current.choice == SourceChoice.PlayerPlaylist },
            playerPlaylistName = current.selectedPlayerPlaylist?.name.takeIf { current.choice == SourceChoice.PlayerPlaylist },
        )
        viewModelScope.launch {
            if (scheduler.start(request) == StartOutcome.AlreadyRunning) showMessage(ALREADY_RUNNING_MESSAGE)
        }
    }

    /** Adds the song with [trackId] to the playlist now, so a confirmed song is never left behind when the user leaves the result. Does nothing when that song is not the one waiting on screen. */
    fun confirmUnsure(trackId: Long) =
        resolveFirstUnsure(trackId) { saved, unsure ->
            val confirmed = matcher.confirm(unsure)
            val playlist = appendedPlaylist(saved, unsure.candidate.uri)
            saved.copy(
                result = saved.result.copy(added = saved.result.added + confirmed, playlist = playlist),
                unsure = saved.unsure.drop(1),
            )
        }

    fun skipUnsure(trackId: Long) =
        resolveFirstUnsure(trackId) { saved, unsure ->
            updatePlaylistWhenSettled(
                saved.copy(result = saved.result.copy(skippedUnsure = saved.result.skippedUnsure + unsure), unsure = saved.unsure.drop(1)),
            )
        }

    /** Opens the picker for a not-found or unsure song and looks up its Spotify options. */
    fun openPicker(track: PlayerTrack) {
        mutableState.update { it.copy(picker = PickerState(track), confirmation = null) }
        viewModelScope.launch {
            val loaded = loadedPicker(track)
            mutableState.update { current -> if (current.picker?.track == track) current.copy(picker = loaded) else current }
        }
    }

    /** "None of these": the song stays where it was. */
    fun closePicker() = mutableState.update { it.copy(picker = null) }

    /** Adds [option] to the playlist now, marks the song as added by hand, remembers the choice and takes the song off its list. */
    fun pickOption(option: SpotifyTrack) {
        viewModelScope.launch {
            decisionLock.withLock {
                val track = mutableState.value.picker?.track ?: return@launch
                val saved = store.saved.first() ?: return@launch
                try {
                    store.save(addedByHand(saved, track, option))
                    mutableState.update { it.copy(picker = null, confirmation = "Added to ${saved.playlistName}") }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    mutableState.update { it.copy(picker = it.picker?.copy(errorMessage = pickerFailureText(exception))) }
                }
            }
        }
    }

    private suspend fun loadedPicker(track: PlayerTrack): PickerState =
        try {
            PickerState(track, options = optionSearch.optionsFor(track), isLoading = false)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            PickerState(track, isLoading = false, errorMessage = pickerFailureText(exception))
        }

    private suspend fun addedByHand(saved: SavedSend, track: PlayerTrack, option: SpotifyTrack): SavedSend {
        val wasUnsure = saved.unsure.any { it.track == track }
        val withPick = withPickedSong(saved, MatchedSong(track, option.uri, wasConfirmedByUser = true))
        val updated = when {
            wasUnsure && withPick.unsure.isEmpty() -> updatePlaylistWhenSettled(withPick)
            else -> withPick.copy(result = withPick.result.copy(playlist = appendedPlaylist(saved, option.uri)))
        }
        matcher.confirm(UnsureSong(track, option))
        return updated
    }

    private fun withPickedSong(saved: SavedSend, picked: MatchedSong): SavedSend =
        saved.copy(
            result = saved.result.copy(added = saved.result.added + picked, notFound = saved.result.notFound.filterNot { it == picked.track }),
            unsure = saved.unsure.filterNot { it.track == picked.track },
        )

    /** Appends [uri] to the playlist, or makes the playlist when there is none yet. Never replaces what is there. */
    private suspend fun appendedPlaylist(saved: SavedSend, uri: String): SpotifyPlaylist {
        val existing = saved.result.playlist
        val knownUris = uniqueUris(saved.result.added)
        return when {
            existing == null -> publisher.publish(saved.source, saved.playlistName, (knownUris + uri).distinct())
            uri in knownUris -> existing
            else -> existing.also { api.addItems(it.id, listOf(uri)) }
        }
    }

    /** Takes songs Play Sift just moved to the trash off the offer, so they are not listed again. */
    fun songsMovedToTrash(trackIds: Set<Long>) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch {
            decisionLock.withLock {
                val saved = store.saved.first() ?: return@launch
                store.save(saved.copy(movedToTrashIds = saved.movedToTrashIds + trackIds))
            }
        }
    }

    fun toggleOffered(trackId: Long) = updateDone { done ->
        done.copy(selectedIds = if (trackId in done.selectedIds) done.selectedIds - trackId else done.selectedIds + trackId)
    }

    fun selectAllOffered() = updateDone { done -> done.copy(selectedIds = done.offer.map { it.track.id }.toSet()) }

    fun clearOffered() = updateDone { done -> done.copy(selectedIds = emptySet()) }

    private fun updateDone(change: (SpotifyStage.Done) -> SpotifyStage.Done) =
        mutableState.update { current -> (current.stage as? SpotifyStage.Done)?.let { current.copy(stage = change(it)) } ?: current }

    private suspend fun stageFor(saved: SavedSend?, work: SendWorkState): SpotifyStage =
        when {
            work is SendWorkState.Matching -> SpotifyStage.Matching(work.done, work.total)
            work == SendWorkState.Publishing -> SpotifyStage.Publishing
            saved == null -> SpotifyStage.Setup
            saved.failureMessage != null -> SpotifyStage.Failed(saved.failureMessage)
            else -> doneStage(saved)
        }

    private suspend fun doneStage(saved: SavedSend): SpotifyStage.Done {
        val offer = offerFor(saved)
        val keptSelection = (mutableState.value.stage as? SpotifyStage.Done)?.selectedIds.orEmpty()
        return SpotifyStage.Done(saved.result, saved.unsure, offer, selectedIds = keptSelection.intersect(offer.map { it.track.id }.toSet()))
    }

    /** Decides the unsure song on screen, which must be the one with [trackId]: a tap that arrives after that song was decided changes nothing. */
    private fun resolveFirstUnsure(trackId: Long, decide: suspend (SavedSend, UnsureSong) -> SavedSend) {
        mutableState.update { it.copy(isDeciding = true) }
        viewModelScope.launch {
            try {
                decisionLock.withLock {
                    val saved = store.saved.first() ?: return@launch
                    val unsure = saved.unsure.firstOrNull()?.takeIf { it.track.id == trackId } ?: return@launch
                    try {
                        store.save(decide(saved, unsure))
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        showMessage(failureText(exception))
                        refreshSignInState()
                    }
                }
            } finally {
                mutableState.update { it.copy(isDeciding = false) }
            }
        }
    }

    private suspend fun updatePlaylistWhenSettled(saved: SavedSend): SavedSend =
        when {
            saved.unsure.isNotEmpty() -> saved
            saved.result.added.isEmpty() -> saved.copy(failureMessage = NONE_MATCHED_MESSAGE)
            saved.result.added.any { it.wasConfirmedByUser } -> {
                val playlist = publisher.publish(saved.source, saved.playlistName, uniqueUris(saved.result.added))
                saved.copy(result = saved.result.copy(playlist = playlist))
            }
            else -> saved
        }

    private suspend fun offerFor(saved: SavedSend): List<OfferedSong> {
        val sizes = knownSizes()
        return trashOffer(saved.result).filterNot { it.id in saved.movedToTrashIds }.map { track -> OfferedSong(track, track.fileLocation()?.let(sizes::get)) }
    }

    /** Sizes are display only, so a failed read shows the songs without sizes. */
    private suspend fun knownSizes(): Map<FileLocation, Long> =
        try {
            fileSizes.sizesByLocation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            emptyMap()
        }

    private suspend fun refreshSignInState() {
        val signedIn = session.isSignedIn()
        val userName = if (signedIn) userNameOrNull() else null
        mutableState.update { it.copy(isSignedIn = signedIn, userName = userName) }
    }

    /** The name is a nicety, so a failure to fetch it never blocks signing in. */
    private suspend fun userNameOrNull(): String? =
        try {
            api.currentUserName()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }

    private fun showMessage(text: String) = mutableState.update { it.copy(message = text) }
}

private const val PLAYED_COUNT_FAILED_MESSAGE = "Couldn't read your play counts. Check that Play Sift can read your music player's library."

private const val PLAYER_PLAYLISTS_FAILED_MESSAGE = "Couldn't read your music player's playlists. Check that Play Sift can read your music player's library."

private const val ALREADY_RUNNING_MESSAGE = "A send to Spotify is already running. Wait for it to finish or cancel it first."
