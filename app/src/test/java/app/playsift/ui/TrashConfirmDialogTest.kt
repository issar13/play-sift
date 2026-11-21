package app.playsift.ui

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.playsift.files.TrackFile
import app.playsift.player.PlayerTrack
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrashConfirmDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun removable(id: Long) = TrackFile.Removable(
        track = PlayerTrack(
            id = id, title = "t$id", artist = "a", album = "b", fileName = "$id.mp3", folderPath = "primary/Music/",
            filePath = null, isCueTrack = false, isUserAdded = false, playCount = 0, lastPlayedAt = null,
            firstSeenAt = Instant.EPOCH, durationMillis = 1_000L,
        ),
        uri = Uri.parse("content://media/$id"),
        sizeBytes = 2_000_000L,
    )

    private fun show(count: Int) = composeRule.setContent {
        val files = (1L..count).map(::removable)
        TrashConfirmDialog(
            plan = TrashPlan(files, emptyList(), files.sumOf { it.sizeBytes }, listOf(files)),
            onConfirm = {},
            onDismiss = {},
        )
    }

    @Test
    fun singleSongUsesSingular() {
        show(1)

        composeRule.onNodeWithText("Move 1 song (2.0 MB) to the trash?", substring = true).assertExists()
    }

    @Test
    fun severalSongsUsePlural() {
        show(2)

        composeRule.onNodeWithText("Move 2 songs (4.0 MB) to the trash?", substring = true).assertExists()
    }
}
