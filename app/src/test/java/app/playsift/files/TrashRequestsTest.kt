package app.playsift.files

import android.net.Uri
import app.playsift.player.PlayerTrack
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrashRequestsTest {
    private fun removable(id: Long) = TrackFile.Removable(
        track = PlayerTrack(
            id = id,
            title = "Title",
            artist = "Artist",
            album = "Album",
            fileName = "$id.mp3",
            folderPath = "primary/Music/",
            filePath = null,
            isCueTrack = false,
            isUserAdded = false,
            playCount = 0,
            lastPlayedAt = null,
            firstSeenAt = Instant.EPOCH,
            durationMillis = 1_000L,
        ),
        uri = Uri.parse("content://media/external_primary/audio/media/$id"),
        sizeBytes = 1L,
    )

    @Test
    fun trashBatchesSplitsAt500() {
        val batches = trashBatches((1L..1201L).map(::removable))

        assertEquals(listOf(500, 500, 201), batches.map { it.size })
    }
}
