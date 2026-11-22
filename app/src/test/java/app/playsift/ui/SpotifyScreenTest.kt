package app.playsift.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.playsift.player.PlayerPlaylist
import app.playsift.player.PlayerTrack
import app.playsift.spotify.SendResult
import app.playsift.spotify.SourceChoice
import app.playsift.spotify.SpotifyPlaylist
import app.playsift.spotify.songTrack
import app.playsift.spotify.PickerState
import app.playsift.spotify.SpotifyStage
import app.playsift.spotify.SpotifyTrack
import app.playsift.spotify.spotifyTrack
import app.playsift.spotify.SpotifyUiState
import app.playsift.spotify.UnsureSong
import app.playsift.spotify.topCountTextFrom
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val setupState = SpotifyUiState(
        builtInClientIdMissing = false,
        clientIdOverride = "",
        isSignedIn = true,
        userName = null,
        choice = SourceChoice.TopPlayed,
        topCountText = "100",
        playlistName = "Play Sift top 100",
        tickedTracks = emptyList(),
        stage = SpotifyStage.Setup,
    )

    private fun actions(
        onTopCountTextChange: (String) -> Unit = {},
        onUseAllPlayedCount: () -> Unit = {},
        onSearchOnSpotify: (PlayerTrack) -> Unit = {},
        onShareNotFound: (List<PlayerTrack>) -> Unit = {},
        onOpenPicker: (PlayerTrack) -> Unit = {},
        onPickOption: (SpotifyTrack) -> Unit = {},
        onClosePicker: () -> Unit = {},
        onConfirmUnsure: (Long) -> Unit = {},
        onSkipUnsure: (Long) -> Unit = {},
    ) = SpotifyActions(
        onSignIn = {}, onSignOut = {}, onClientIdChange = {}, onChooseSource = {}, onChoosePlayerPlaylist = {},
        onTopCountTextChange = onTopCountTextChange, onUseAllPlayedCount = onUseAllPlayedCount,
        onPlaylistNameChange = {}, onSend = {}, onCancelSend = {}, onConfirmUnsure = onConfirmUnsure, onSkipUnsure = onSkipUnsure,
        onOpenPlaylist = {}, onToggleOffered = {}, onSelectAllOffered = {}, onMoveToTrash = {}, onBackToSetup = {},
        onSearchOnSpotify = onSearchOnSpotify, onShareNotFound = onShareNotFound,
        onOpenPicker = onOpenPicker, onPickOption = onPickOption, onClosePicker = onClosePicker,
    )

    private fun showSetup(initial: SpotifyUiState = setupState, allPlayedCount: Int = 321) = composeRule.setContent {
        var state by remember { mutableStateOf(initial) }
        SpotifyScreen(
            state = state,
            isPreparingTrash = false,
            actions = actions(
                onTopCountTextChange = { input -> state = state.copy(topCountText = topCountTextFrom(input)) },
                onUseAllPlayedCount = { state = state.copy(topCountText = allPlayedCount.toString()) },
            ),
        )
    }

    @Test
    fun clearingTheFieldAndTypingAnotherCountUpdatesTheSendButton() {
        showSetup()

        composeRule.onNodeWithTag("topCountField").performTextClearance()
        composeRule.onNodeWithText("Send 100 songs to Spotify").assertDoesNotExist()
        composeRule.onNodeWithText("Create playlist").assertIsNotEnabled()
        composeRule.onNodeWithText("Enter a number above 0 to send.").assertExists()
        composeRule.onNodeWithTag("topCountField").performTextInput("50")

        composeRule.onNodeWithText("Send 50 songs to Spotify").assertIsEnabled()
    }

    @Test
    fun playerPlaylistsAreListedWithTheirSongCounts() {
        showSetup(
            setupState.copy(
                choice = SourceChoice.PlayerPlaylist,
                playerPlaylists = listOf(PlayerPlaylist(1, "Gym", 12)),
            ),
        )

        composeRule.onNodeWithText("Your player's playlists").assertExists()
        composeRule.onNodeWithText("Gym \u00B7 12 songs").assertExists()
    }

    @Test
    fun anEmptyPlayerPlaylistListSaysSo() {
        showSetup(setupState.copy(choice = SourceChoice.PlayerPlaylist))

        composeRule.onNodeWithText("No playlists in your music player.").assertExists()
    }

    @Test
    fun theHintUnderTheNameFieldSaysWhetherAPlaylistIsCreatedOrUpdated() {
        showSetup(setupState.copy(willUpdatePlaylist = false))
        composeRule.onNodeWithText("Creates a new playlist").assertExists()
        composeRule.onNodeWithText("Updates your existing playlist").assertDoesNotExist()
    }

    @Test
    fun theHintSaysUpdateWhenTheNameIsRemembered() {
        showSetup(setupState.copy(willUpdatePlaylist = true))
        composeRule.onNodeWithText("Updates your existing playlist").assertExists()
        composeRule.onNodeWithText("Creates a new playlist").assertDoesNotExist()
    }

    @Test
    fun tappingAPresetChipSetsTheCount() {
        showSetup()

        composeRule.onNodeWithText("25").performClick()

        composeRule.onNodeWithText("Send 25 songs to Spotify").assertIsEnabled()
    }

    @Test
    fun theAllChipUsesTheNumberOfPlayedSongs() {
        showSetup()

        composeRule.onNodeWithText("All").performClick()

        composeRule.onNodeWithText("Send 321 songs to Spotify").assertIsEnabled()
    }

    @Test
    fun aZeroCountDisablesSending() {
        showSetup(setupState.copy(topCountText = "0"))

        composeRule.onNodeWithText("Create playlist").assertIsNotEnabled()
    }

    private val notFoundSong = songTrack(7, title = "Lost Song", artist = "Lost Artist")

    private fun showDone(
        onSearchOnSpotify: (PlayerTrack) -> Unit = {},
        onShareNotFound: (List<PlayerTrack>) -> Unit = {},
        onOpenPicker: (PlayerTrack) -> Unit = {},
    ) = composeRule.setContent {
        val result = SendResult(
            playlist = SpotifyPlaylist("p1", "https://open.spotify.com/playlist/p1"),
            added = emptyList(),
            skippedUnsure = emptyList(),
            notFound = listOf(notFoundSong),
        )
        SpotifyScreen(
            state = setupState.copy(stage = SpotifyStage.Done(result, unsure = emptyList(), offer = emptyList(), selectedIds = emptySet())),
            isPreparingTrash = false,
            actions = actions(onSearchOnSpotify = onSearchOnSpotify, onShareNotFound = onShareNotFound, onOpenPicker = onOpenPicker),
        )
    }

    @Test
    fun theNotFoundSectionIsCollapsedUntilTapped() {
        showDone()

        composeRule.onNodeWithText("Lost Song").assertDoesNotExist()
        composeRule.onNodeWithText("Not found (1)").performClick()

        composeRule.onNodeWithText("Lost Song").assertIsDisplayed()
        composeRule.onNodeWithText("Lost Artist").assertIsDisplayed()
    }

    @Test
    fun theNotFoundListCanBeShared() {
        var shared: List<PlayerTrack>? = null
        showDone(onShareNotFound = { shared = it })

        composeRule.onNodeWithText("Not found (1)").performClick()
        composeRule.onNodeWithText("Share list").performClick()

        assertEquals(listOf(notFoundSong), shared)
    }

    @Test
    fun tappingANotFoundSongOpensThePicker() {
        var opened: PlayerTrack? = null
        showDone(onOpenPicker = { opened = it })

        composeRule.onNodeWithText("Not found (1)").performClick()
        composeRule.onNodeWithText("Lost Song").performClick()

        assertEquals(notFoundSong, opened)
    }

    private fun showPicker(
        picker: PickerState,
        onPickOption: (SpotifyTrack) -> Unit = {},
        onClosePicker: () -> Unit = {},
        onSearchOnSpotify: (PlayerTrack) -> Unit = {},
    ) = composeRule.setContent {
        val result = SendResult(null, added = emptyList(), skippedUnsure = emptyList(), notFound = listOf(notFoundSong))
        SpotifyScreen(
            state = setupState.copy(stage = SpotifyStage.Done(result, unsure = emptyList(), offer = emptyList(), selectedIds = emptySet()), picker = picker),
            isPreparingTrash = false,
            actions = actions(onPickOption = onPickOption, onClosePicker = onClosePicker, onSearchOnSpotify = onSearchOnSpotify),
        )
    }

    private val liveOption = spotifyTrack("Lost Song - Live", artist = "Lost Artist", durationMillis = 272_000, album = "Live Album")
    private val studioOption = spotifyTrack("Lost Song", artist = "Lost Artist", durationMillis = 200_000, album = "Studio Album")

    @Test
    fun thePickerShowsTitleArtistAlbumLengthAndALengthHint() {
        showPicker(PickerState(notFoundSong.copy(durationMillis = 200_000), listOf(liveOption, studioOption), isLoading = false))

        composeRule.onNodeWithText("Lost Song - Live").assertIsDisplayed()
        composeRule.onNodeWithText("Lost Artist - Live Album").assertIsDisplayed()
        composeRule.onNodeWithText("4:32  1:12 longer").assertIsDisplayed()
        composeRule.onNodeWithText("3:20").assertIsDisplayed()
    }

    @Test
    fun tappingAnOptionAddsIt() {
        var picked: SpotifyTrack? = null
        showPicker(PickerState(notFoundSong, listOf(liveOption, studioOption), isLoading = false), onPickOption = { picked = it })

        composeRule.onNodeWithText("Lost Song - Live").performClick()

        assertEquals(liveOption, picked)
    }

    @Test
    fun noneOfTheseClosesAndTheSearchLinkStaysAvailable() {
        var isClosed = false
        var searched: PlayerTrack? = null
        showPicker(PickerState(notFoundSong, listOf(studioOption), isLoading = false), onClosePicker = { isClosed = true }, onSearchOnSpotify = { searched = it })

        composeRule.onNodeWithText("Open search in Spotify").performClick()
        composeRule.onNodeWithText("None of these").performClick()

        assertEquals(notFoundSong, searched)
        assertEquals(true, isClosed)
    }

    @Test
    fun aFailedSearchShowsItsMessageInThePicker() {
        showPicker(PickerState(notFoundSong, isLoading = false, errorMessage = "Spotify is busy right now. Try again in a minute."))

        composeRule.onNodeWithText("Spotify is busy right now. Try again in a minute.").assertIsDisplayed()
    }

    private val unsureSong = UnsureSong(songTrack(9, title = "Maybe"), spotifyTrack("Maybe", durationMillis = 90_000))

    private fun showUnsure(isDeciding: Boolean, onConfirmUnsure: (Long) -> Unit = {}, onSkipUnsure: (Long) -> Unit = {}) = composeRule.setContent {
        val result = SendResult(SpotifyPlaylist("p1", "url"), added = emptyList(), skippedUnsure = emptyList(), notFound = emptyList())
        SpotifyScreen(
            state = setupState.copy(stage = SpotifyStage.Done(result, listOf(unsureSong), offer = emptyList(), selectedIds = emptySet()), isDeciding = isDeciding),
            isPreparingTrash = false,
            actions = actions(onConfirmUnsure = onConfirmUnsure, onSkipUnsure = onSkipUnsure),
        )
    }

    @Test
    fun theDecisionButtonsNameTheSongOnScreen() {
        var confirmed: Long? = null
        var skipped: Long? = null
        showUnsure(isDeciding = false, onConfirmUnsure = { confirmed = it }, onSkipUnsure = { skipped = it })

        composeRule.onNodeWithText("Add it").performScrollTo().performClick()
        composeRule.onNodeWithText("Skip").performScrollTo().performClick()

        assertEquals(9L, confirmed)
        assertEquals(9L, skipped)
    }

    @Test
    fun theDecisionButtonsAreDisabledWhileADecisionIsBeingSaved() {
        showUnsure(isDeciding = true)

        composeRule.onNodeWithText("Add it").assertIsNotEnabled()
        composeRule.onNodeWithText("Skip").assertIsNotEnabled()
    }

    @Test
    fun aSendWhereNothingMatchedStillListsTheNotFoundSongs() {
        composeRule.setContent {
            val result = SendResult(null, added = emptyList(), skippedUnsure = emptyList(), notFound = listOf(notFoundSong))
            SpotifyScreen(
                state = setupState.copy(stage = SpotifyStage.Done(result, unsure = emptyList(), offer = emptyList(), selectedIds = emptySet())),
                isPreparingTrash = false,
                actions = actions(),
            )
        }

        composeRule.onNodeWithText("No songs matched, so no playlist was made").assertIsDisplayed()
        composeRule.onNodeWithText("Not found (1)").assertIsDisplayed()
    }
}
