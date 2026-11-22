package app.playsift.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import app.playsift.player.AccessState
import app.playsift.player.HistoryHealth
import app.playsift.player.LibraryList
import app.playsift.player.PlayerTrack
import app.playsift.settings.CutoffOption
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val track = PlayerTrack(
        id = 1,
        title = "Blue Monday",
        artist = "New Order",
        album = "Power, Corruption & Lies",
        fileName = "blue-monday.mp3",
        folderPath = "primary/Music/",
        filePath = null,
        isCueTrack = false,
        isUserAdded = false,
        playCount = 7,
        lastPlayedAt = null,
        firstSeenAt = Instant.parse("2024-01-01T00:00:00Z"),
        durationMillis = 300_000,
    )

    private fun state(
        list: LibraryList = LibraryList.DeleteCandidates,
        health: HistoryHealth = HistoryHealth.Healthy,
        selectedIds: Set<Long> = emptySet(),
    ) = LibraryUiState(
        access = AccessState.Ready,
        list = list,
        tracks = listOf(track, track.copy(id = 2, title = "Temptation", playCount = 3)),
        historyHealth = health,
        cutoff = CutoffOption.OneYear,
        selectedIds = selectedIds,
        isLoading = false,
    )

    private fun show(state: LibraryUiState) = composeRule.setContent {
        TrackListScreen(
            state = state,
            today = LocalDate.of(2026, 6, 15),
            onToggleSelected = {},
            onSelectAll = {},
            onMoveToTrash = {},
        )
    }

    @Test
    fun deleteButtonDisabledWithNothingSelected() {
        show(state())
        composeRule.onNodeWithText("Move 0 to trash").assertIsNotEnabled()
    }

    @Test
    fun deleteButtonShowsSelectedCount() {
        show(state(selectedIds = setOf(1L, 2L)))
        composeRule.onNodeWithText("Move 2 to trash").assertIsEnabled()
    }

    @Test
    fun mostPlayedActionBarOffersSendToSpotifyOnly() {
        show(state(list = LibraryList.MostPlayed, selectedIds = setOf(1L, 2L)))
        composeRule.onNodeWithText("Send 2 to Spotify").assertIsEnabled()
        composeRule.onNodeWithText("Move 2 to trash").assertDoesNotExist()
    }

    @Test
    fun neverPlayedActionBarOffersSendToSpotifyOnly() {
        show(state(list = LibraryList.NeverPlayed, selectedIds = setOf(1L)))
        composeRule.onNodeWithText("Send 1 to Spotify").assertIsEnabled()
        composeRule.onNodeWithText("Move 1 to trash").assertDoesNotExist()
    }

    @Test
    fun deleteActionBarKeepsBothButtons() {
        show(state(selectedIds = setOf(1L)))
        composeRule.onNodeWithText("Send 1 to Spotify").assertIsEnabled()
        composeRule.onNodeWithText("Move 1 to trash").assertIsEnabled()
    }

    @Test
    fun bannerShownWhenMostlyUnplayed() {
        show(state(list = LibraryList.NeverPlayed, health = HistoryHealth.MostlyUnplayed))
        composeRule.onNodeWithText(
            "Almost nothing has a play count. If you reset your player's library, play counts started over.",
        ).assertExists()
    }

    @Test
    fun mostPlayedRowShowsPlayCount() {
        show(state(list = LibraryList.MostPlayed))
        composeRule.onNodeWithText("7 plays").assertExists()
    }

    @Test
    fun mostPlayedShowsGroupHeaderAndCollapses() {
        val groupedTracks = (1L..7L).map { id -> track.copy(id = id, title = "Song $id", playCount = 12) }
        show(state(list = LibraryList.MostPlayed).copy(tracks = groupedTracks))
        composeRule.onNodeWithText("12 plays \u00b7 7 songs").assertIsDisplayed()
        composeRule.onNodeWithText("Song 1").assertIsDisplayed()
        composeRule.onNodeWithText("Song 2").assertIsDisplayed()
        composeRule.onNodeWithText("Song 3").assertDoesNotExist()
        composeRule.onNodeWithText("Show 5 more").performClick()
        composeRule.onNodeWithText("Song 3").assertIsDisplayed()
        composeRule.onNodeWithText("Show 5 more").assertDoesNotExist()
        composeRule.onNodeWithText("12 plays \u00b7 7 songs").performClick()
        composeRule.onNodeWithText("Song 3").assertDoesNotExist()
    }

    @Test
    fun mostPlayedGroupHeaderUsesSingularForOnePlay() {
        val playedOnce = (1L..6L).map { id -> track.copy(id = id, title = "Song $id", playCount = 1) }
        show(state(list = LibraryList.MostPlayed).copy(tracks = playedOnce))
        composeRule.onNodeWithText("1 play \u00b7 6 songs").assertIsDisplayed()
    }
}
