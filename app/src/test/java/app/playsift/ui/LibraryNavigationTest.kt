package app.playsift.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
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
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryNavigationTest {
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

    private val counts = LibraryCounts(mostPlayed = 80, neverPlayed = 40, deleteCandidates = 410, large = 3)

    private val summary = HomeSummary(
        totalSongs = 120,
        playedSongs = 80,
        neverPlayedSongs = 40,
        deleteCandidates = 410,
        deleteCandidatesSizeBytes = 48_000_000,
        librarySizeBytes = 1_234_500_000,
        largeSongs = 3,
        largeSongsSizeBytes = 21_500_000,
        mostPlayed = track,
        topArtist = ArtistPlays("New Order", 15),
        trackingSince = Instant.parse("2024-01-01T00:00:00Z"),
    )

    private val tracksByScreen = mapOf(
        Screen.MostPlayed to listOf(track.copy(id = 1, title = "Heavy Rotation")),
        Screen.NeverPlayed to listOf(track.copy(id = 2, title = "Unheard Gem", playCount = 0)),
        Screen.Delete to listOf(track.copy(id = 3, title = "Old Forgotten", playCount = 0)),
        Screen.Large to listOf(track.copy(id = 4, title = "Long Mix")),
    )

    private fun state(screen: Screen) = LibraryUiState(
        access = AccessState.Ready,
        list = LibraryList.AllTracks,
        tracks = tracksByScreen[screen].orEmpty(),
        historyHealth = HistoryHealth.Healthy,
        cutoff = CutoffOption.OneYear,
        selectedIds = emptySet(),
        isLoading = false,
        screen = screen,
        home = summary,
        sizesById = mapOf(4L to 6_000_000L),
        hasFileAccess = true,
        libraryCounts = counts,
    )

    /** Mirrors how PlaySiftApp switches between Home and the Library chips, without the view model. */
    private fun showApp(onScreenChange: (Screen) -> Unit = {}) = composeRule.setContent {
        PlaySiftTheme {
            var screen by remember { mutableStateOf(Screen.Home) }
            val open: (Screen) -> Unit = { target ->
                screen = target
                onScreenChange(target)
            }
            if (screen == Screen.Home) {
                HomeScreen(state = state(screen), onOpenDelete = { open(Screen.Delete) }, onOpenLarge = { open(Screen.Large) }, onAllowFileAccess = {})
            } else {
                Column {
                    LibraryChips(selected = screen, counts = counts, onSelect = open)
                    if (screen == Screen.Large) {
                        LargeSongsScreen(state = state(screen), onToggleSelected = {}, onSelectAll = {}, onMoveToTrash = {})
                    } else {
                        TrackListScreen(state = state(screen), today = LocalDate.of(2026, 6, 15), onToggleSelected = {}, onSelectAll = {}, onMoveToTrash = {})
                    }
                }
            }
        }
    }

    @Test
    fun chipShowsCount() {
        composeRule.setContent { PlaySiftTheme { LibraryChips(selected = Screen.Delete, counts = counts, onSelect = {}) } }
        composeRule.onNodeWithText("Delete 410").assertIsSelected()
        composeRule.onNodeWithText("Most played 80").assertExists()
        composeRule.onNodeWithText("Never played 40").assertExists()
        composeRule.onNodeWithText("Large 3").assertExists()
    }

    @Test
    fun chipWithoutKnownCountShowsOnlyItsName() {
        composeRule.setContent { PlaySiftTheme { LibraryChips(selected = Screen.MostPlayed, counts = counts.copy(large = null), onSelect = {}) } }
        composeRule.onNodeWithText("Large").assertExists()
    }

    @Test
    fun libraryChipsSwitchLists() {
        showApp()
        composeRule.onNodeWithTag("deleteCard").performClick()
        composeRule.onNodeWithText("Old Forgotten").assertExists()
        composeRule.onNodeWithText("Never played 40").performClick()
        composeRule.onNodeWithText("Unheard Gem").assertExists()
        composeRule.onNodeWithText("Old Forgotten").assertDoesNotExist()
        composeRule.onNodeWithText("Most played 80").performClick()
        composeRule.onNodeWithText("Heavy Rotation").assertExists()
    }

    @Test
    fun homeDeleteCardOpensLibraryDeleteChip() {
        var openedScreen: Screen? = null
        showApp { openedScreen = it }
        composeRule.onNodeWithTag("deleteCard").performClick()
        assertEquals(Screen.Delete, openedScreen)
        composeRule.onNodeWithText("Delete 410").assertIsSelected()
    }

    @Test
    fun homeLargeCardOpensLibraryLargeChip() {
        var openedScreen: Screen? = null
        showApp { openedScreen = it }
        composeRule.onNodeWithTag("largeCard").performClick()
        assertEquals(Screen.Large, openedScreen)
        composeRule.onNodeWithText("Large 3").assertIsSelected()
        composeRule.onNodeWithText("Long Mix").assertExists()
    }
}
