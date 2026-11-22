package app.playsift.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.playsift.player.AccessState
import app.playsift.player.HistoryHealth
import app.playsift.player.LibraryList
import app.playsift.player.PlayerTrack
import app.playsift.settings.CutoffOption
import app.playsift.ui.theme.PlaySiftTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
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

    private val summary = HomeSummary(
        totalSongs = 120,
        playedSongs = 80,
        neverPlayedSongs = 40,
        deleteCandidates = 12,
        deleteCandidatesSizeBytes = 48_000_000,
        librarySizeBytes = 1_234_500_000,
        largeSongs = 3,
        largeSongsSizeBytes = 21_500_000,
        mostPlayed = track,
        topArtist = ArtistPlays("New Order", 15),
        trackingSince = Instant.parse("2024-01-01T00:00:00Z"),
    )

    private fun state(
        screen: Screen = Screen.Home,
        home: HomeSummary? = summary,
        hasFileAccess: Boolean = true,
        tracks: List<PlayerTrack> = emptyList(),
        sizesById: Map<Long, Long> = emptyMap(),
        selectedIds: Set<Long> = emptySet(),
    ) = LibraryUiState(
        access = AccessState.Ready,
        list = LibraryList.Large,
        tracks = tracks,
        historyHealth = HistoryHealth.Healthy,
        cutoff = CutoffOption.OneYear,
        selectedIds = selectedIds,
        isLoading = false,
        screen = screen,
        home = home,
        sizesById = sizesById,
        hasFileAccess = hasFileAccess,
    )

    @Test
    fun homeShowsCountsAndSizes() {
        composeRule.setContent {
            PlaySiftTheme { HomeScreen(state = state(), onOpenDelete = {}, onOpenLarge = {}, onAllowFileAccess = {}) }
        }
        composeRule.onNodeWithText("120 songs").assertExists()
        composeRule.onNodeWithText("1234.5 MB in known files").assertExists()
        composeRule.onNodeWithText("80").assertExists()
        composeRule.onNodeWithText("40").assertExists()
        composeRule.onNodeWithText("12").assertExists()
        composeRule.onNodeWithText("48.0 MB in known files").assertExists()
        composeRule.onNodeWithText("21.5 MB").assertExists()
        composeRule.onNodeWithText("Most played song: Blue Monday by New Order (7 plays)").assertExists()
        composeRule.onNodeWithText("Top artist: New Order (15 plays)").assertExists()
    }

    @Test
    fun homeWithAccessButNoSizesShowsSizesUnavailable() {
        val withoutSizes = summary.copy(largeSongs = null, largeSongsSizeBytes = null)
        composeRule.setContent {
            PlaySiftTheme {
                HomeScreen(
                    state = state(home = withoutSizes, hasFileAccess = true),
                    onOpenDelete = {},
                    onOpenLarge = {},
                    onAllowFileAccess = {},
                )
            }
        }
        composeRule.onNodeWithText("Sizes unavailable").assertExists()
        composeRule.onNodeWithText("Allow file access").assertDoesNotExist()
    }

    @Test
    fun homeWithoutAccessShowsAllowFileAccess() {
        var allowTapCount = 0
        val withoutSizes = summary.copy(
            deleteCandidatesSizeBytes = null,
            librarySizeBytes = null,
            largeSongs = null,
            largeSongsSizeBytes = null,
        )
        composeRule.setContent {
            PlaySiftTheme {
                HomeScreen(
                    state = state(home = withoutSizes, hasFileAccess = false),
                    onOpenDelete = {},
                    onOpenLarge = {},
                    onAllowFileAccess = { allowTapCount += 1 },
                )
            }
        }
        composeRule.onNodeWithText("120 songs").assertExists()
        composeRule.onNodeWithText("1234.5 MB in known files").assertDoesNotExist()
        composeRule.onNodeWithText("Allow file access").performClick()
        assertEquals(1, allowTapCount)
    }

    @Test
    fun tappingLargeCardOpensLargeSongs() {
        val largeTrack = track.copy(id = 2, title = "Long Mix")
        composeRule.setContent {
            PlaySiftTheme {
                var screen by remember { mutableStateOf(Screen.Home) }
                if (screen == Screen.Home) {
                    HomeScreen(state = state(), onOpenDelete = {}, onOpenLarge = { screen = Screen.Large }, onAllowFileAccess = {})
                } else {
                    LargeSongsScreen(
                        state = state(screen = Screen.Large, tracks = listOf(largeTrack), sizesById = mapOf(2L to 6_000_000L)),
                        onBack = { screen = Screen.Home },
                        onToggleSelected = {},
                        onSelectAll = {},
                        onMoveToTrash = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("largeCard").performClick()
        composeRule.onNodeWithText("Long Mix").assertExists()
        composeRule.onNodeWithText("6.0 MB \u00b7 7 plays").assertExists()
    }

    @Test
    fun largeSongsMoveButtonShowsCountAndMegabytes() {
        val tracks = listOf(track.copy(id = 1), track.copy(id = 2, title = "Long Mix"))
        composeRule.setContent {
            PlaySiftTheme {
                LargeSongsScreen(
                    state = state(
                        screen = Screen.Large,
                        tracks = tracks,
                        sizesById = mapOf(1L to 6_000_000L, 2L to 5_500_000L),
                        selectedIds = setOf(1L, 2L),
                    ),
                    onBack = {},
                    onToggleSelected = {},
                    onSelectAll = {},
                    onMoveToTrash = {},
                )
            }
        }
        composeRule.onNodeWithText("Move 2 to trash (11.5 MB)").assertExists()
    }

    @Test
    fun deleteCardUsesErrorColour() {
        var errorContainer = Color.Unspecified
        composeRule.setContent {
            PlaySiftTheme {
                errorContainer = MaterialTheme.colorScheme.errorContainer
                HomeScreen(state = state(), onOpenDelete = {}, onOpenLarge = {}, onAllowFileAccess = {})
            }
        }
        val deleteColour = composeRule.onNodeWithTag("deleteCard").fetchSemanticsNode().config[CardContainerColor]
        val largeColour = composeRule.onNodeWithTag("largeCard").fetchSemanticsNode().config[CardContainerColor]
        assertEquals(errorContainer, deleteColour)
        assertNotEquals(errorContainer, largeColour)
        assertTrue(deleteColour != Color.Unspecified)
    }

    @Test
    fun largeSongRowsShowSizeAndPlayCount() {
        val tracks = listOf(
            track.copy(id = 1, title = "Heavy", playCount = 12),
            track.copy(id = 2, title = "Once", playCount = 1),
            track.copy(id = 3, title = "Unheard", playCount = 0),
        )
        composeRule.setContent {
            PlaySiftTheme {
                LargeSongsScreen(
                    state = state(
                        screen = Screen.Large,
                        tracks = tracks,
                        sizesById = mapOf(1L to 8_400_000L, 2L to 6_000_000L, 3L to 5_500_000L),
                    ),
                    onBack = {},
                    onToggleSelected = {},
                    onSelectAll = {},
                    onMoveToTrash = {},
                )
            }
        }
        composeRule.onNodeWithText("8.4 MB \u00b7 12 plays").assertExists()
        composeRule.onNodeWithText("6.0 MB \u00b7 1 play").assertExists()
        composeRule.onNodeWithText("5.5 MB \u00b7 Never played").assertExists()
    }

    @Test
    fun largeScreenShowsUnavailableInsteadOfEmptyMessage() {
        composeRule.setContent {
            PlaySiftTheme {
                LargeSongsScreen(
                    state = state(screen = Screen.Large, tracks = emptyList()).copy(sizesUnavailable = true),
                    onBack = {},
                    onToggleSelected = {},
                    onSelectAll = {},
                    onMoveToTrash = {},
                )
            }
        }
        composeRule.onNodeWithText("Sizes unavailable. Couldn't read your file sizes.").assertExists()
        composeRule.onNodeWithText("No songs of 5 MB or more.").assertDoesNotExist()
    }
}
