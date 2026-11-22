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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.playsift.player.PlayerTrack
import app.playsift.spotify.SendResult
import app.playsift.spotify.SourceChoice
import app.playsift.spotify.SpotifyPlaylist
import app.playsift.spotify.songTrack
import app.playsift.spotify.SpotifyStage
import app.playsift.spotify.SpotifyUiState
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
    ) = SpotifyActions(
        onSignIn = {}, onSignOut = {}, onClientIdChange = {}, onChooseSource = {},
        onTopCountTextChange = onTopCountTextChange, onUseAllPlayedCount = onUseAllPlayedCount,
        onPlaylistNameChange = {}, onSend = {}, onCancelSend = {}, onConfirmUnsure = {}, onSkipUnsure = {},
        onOpenPlaylist = {}, onToggleOffered = {}, onSelectAllOffered = {}, onMoveToTrash = {}, onBackToSetup = {},
        onSearchOnSpotify = onSearchOnSpotify, onShareNotFound = onShareNotFound,
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

    private fun showDone(onSearchOnSpotify: (PlayerTrack) -> Unit = {}, onShareNotFound: (List<PlayerTrack>) -> Unit = {}) = composeRule.setContent {
        val result = SendResult(
            playlist = SpotifyPlaylist("p1", "https://open.spotify.com/playlist/p1"),
            added = emptyList(),
            skippedUnsure = emptyList(),
            notFound = listOf(notFoundSong),
        )
        SpotifyScreen(
            state = setupState.copy(stage = SpotifyStage.Done(result, unsure = emptyList(), offer = emptyList(), selectedIds = emptySet())),
            isPreparingTrash = false,
            actions = actions(onSearchOnSpotify = onSearchOnSpotify, onShareNotFound = onShareNotFound),
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
    fun eachNotFoundSongCanBeSearchedAndTheListShared() {
        var searched: PlayerTrack? = null
        var shared: List<PlayerTrack>? = null
        showDone(onSearchOnSpotify = { searched = it }, onShareNotFound = { shared = it })

        composeRule.onNodeWithText("Not found (1)").performClick()
        composeRule.onNodeWithText("Search on Spotify").performClick()
        composeRule.onNodeWithText("Share list").performClick()

        assertEquals(notFoundSong, searched)
        assertEquals(listOf(notFoundSong), shared)
    }
}
