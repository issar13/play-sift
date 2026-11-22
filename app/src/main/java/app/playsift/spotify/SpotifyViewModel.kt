package app.playsift.spotify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playsift.files.FileLocation
import app.playsift.files.FileSizes
import app.playsift.files.fileLocation
import app.playsift.player.PlayerTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val fileSizes: FileSizes,
    private val loadMergedTracks: suspend () -> List<PlayerTrack>,
    private val builtInClientId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SpotifyUiState(
            builtInClientIdMissing = builtInClientId.isBlank(),
            clientIdOverride = "",
            isSignedIn = false,
            userName = null,
            choice = SourceChoice.TopPlayed,
            topCount = DEFAULT_TOP_COUNT,
            playlistName = SendSource.TopPlayed(DEFAULT_TOP_COUNT).defaultName,
            tickedTracks = emptyList(),
            stage = SpotifyStage.Setup,
        ),
    )
    val state: StateFlow<SpotifyUiState> = mutableState

    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            mutableState.update { it.copy(clientIdOverride = clientId.override()) }
            refreshSignInState()
        }
    }

    /** Saves a fresh PKCE verifier, then hands the Spotify sign-in page address to [openBrowser]. */
    fun signIn(openBrowser: (String) -> Unit) {
        viewModelScope.launch {
            val effectiveClientId = clientId.effective()
            if (effectiveClientId.isBlank()) return@launch
            val pending = PendingAuthorization(verifier = newCodeVerifier(), state = newAuthorizationState())
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
                session.completeSignIn(code, pending.verifier)
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
            tokenStore.clear()
            mutableState.update { it.copy(isSignedIn = false, userName = null, stage = SpotifyStage.Setup) }
        }
    }

    fun setClientIdOverride(text: String) {
        mutableState.update { it.copy(clientIdOverride = text) }
        viewModelScope.launch { clientId.setOverride(text) }
    }

    /** Called by the lists when the user ticks songs and taps Send to Spotify. */
    fun useTickedSongs(tracks: List<PlayerTrack>) {
        sendJob?.cancel()
        mutableState.update {
            it.copy(choice = SourceChoice.TickedSongs, tickedTracks = tracks, playlistName = SendSource.TickedSongs.defaultName, stage = SpotifyStage.Setup)
        }
    }

    fun chooseSource(choice: SourceChoice) =
        mutableState.update { it.copy(choice = choice, playlistName = sourceFor(choice, it.topCount).defaultName) }

    fun setTopCount(count: Int) =
        mutableState.update { it.copy(topCount = count, playlistName = if (it.choice == SourceChoice.TopPlayed) SendSource.TopPlayed(count).defaultName else it.playlistName) }

    fun setPlaylistName(name: String) = mutableState.update { it.copy(playlistName = name) }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    fun backToSetup() {
        sendJob?.cancel()
        mutableState.update { it.copy(stage = SpotifyStage.Setup) }
    }

    fun send() {
        val current = mutableState.value
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            guardedStage {
                val source = sourceFor(current.choice, current.topCount)
                mutableState.update { it.copy(stage = SpotifyStage.Matching(done = 0, total = 0)) }
                val tracks = tracksFor(source, mergedTracksIfNeeded(source), current.tickedTracks)
                val report = matcher.match(tracks) { done, total -> mutableState.update { it.copy(stage = SpotifyStage.Matching(done, total)) } }
                continueAfterMatching(
                    SpotifyStage.ConfirmingUnsure(source, current.playlistName.trim(), report.matched, report.unsure, emptyList(), report.notFound),
                )
            }
        }
    }

    fun confirmUnsure() {
        val confirming = mutableState.value.stage as? SpotifyStage.ConfirmingUnsure ?: return
        viewModelScope.launch {
            guardedStage {
                val confirmed = matcher.confirm(confirming.pending.first())
                continueAfterMatching(confirming.copy(matched = confirming.matched + confirmed, pending = confirming.pending.drop(1)))
            }
        }
    }

    fun skipUnsure() {
        val confirming = mutableState.value.stage as? SpotifyStage.ConfirmingUnsure ?: return
        viewModelScope.launch {
            guardedStage {
                continueAfterMatching(confirming.copy(pending = confirming.pending.drop(1), skipped = confirming.skipped + confirming.pending.first()))
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

    private fun sourceFor(choice: SourceChoice, topCount: Int): SendSource =
        when (choice) {
            SourceChoice.TopPlayed -> SendSource.TopPlayed(topCount)
            SourceChoice.EverythingPlayed -> SendSource.EverythingPlayed
            SourceChoice.TickedSongs -> SendSource.TickedSongs
        }

    private suspend fun mergedTracksIfNeeded(source: SendSource): List<PlayerTrack> =
        if (source == SendSource.TickedSongs) emptyList() else loadMergedTracks()

    /** Asks about the next unsure song, or makes the playlist once none are left. */
    private suspend fun continueAfterMatching(confirming: SpotifyStage.ConfirmingUnsure) {
        if (confirming.pending.isNotEmpty()) {
            mutableState.update { it.copy(stage = confirming) }
        } else if (confirming.matched.isEmpty()) {
            mutableState.update { it.copy(stage = SpotifyStage.Failed("None of these songs could be matched on Spotify, so no playlist was made.")) }
        } else {
            publish(confirming)
        }
    }

    private suspend fun publish(confirming: SpotifyStage.ConfirmingUnsure) {
        mutableState.update { it.copy(stage = SpotifyStage.Publishing) }
        val name = confirming.playlistName.ifBlank { confirming.source.defaultName }
        val playlist = publisher.publish(confirming.source, name, uniqueUris(confirming.matched))
        val result = SendResult(playlist, confirming.matched, confirming.skipped, confirming.notFound)
        mutableState.update { it.copy(stage = SpotifyStage.Done(result, offerFor(result), selectedIds = emptySet())) }
    }

    private suspend fun offerFor(result: SendResult): List<OfferedSong> {
        val sizes = knownSizes()
        return trashOffer(result).map { track -> OfferedSong(track, track.fileLocation()?.let(sizes::get)) }
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

    private suspend fun guardedStage(action: suspend () -> Unit) {
        try {
            action()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SpotifyHttpException) {
            mutableState.update { it.copy(stage = SpotifyStage.Failed(httpFailureText(exception))) }
        } catch (_: Exception) {
            mutableState.update { it.copy(stage = SpotifyStage.Failed("Something went wrong talking to Spotify. Check your connection and try again.")) }
        }
    }

    private fun httpFailureText(exception: SpotifyHttpException): String =
        when (exception.status) {
            STATUS_UNAUTHORIZED -> "Spotify no longer accepts the sign-in. Sign out and sign in again."
            STATUS_FORBIDDEN -> "Spotify refused the request. Make sure your account is added to the app in the Spotify developer dashboard."
            else -> "Spotify answered with an error (${exception.status}). Try again in a minute."
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

private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_FORBIDDEN = 403
