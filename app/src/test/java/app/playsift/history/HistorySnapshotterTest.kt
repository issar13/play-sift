package app.playsift.history

import app.playsift.player.Access
import app.playsift.player.AccessState
import app.playsift.player.Library
import app.playsift.player.LibraryList
import app.playsift.player.PlayerTrack
import java.time.Clock
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySnapshotterTest {
    private val now = Instant.parse("2026-11-21T00:00:00Z")
    private val track = PlayerTrack(
        id = 1, title = "t", artist = "a", album = "b", fileName = "1.mp3", folderPath = "primary/Music/",
        filePath = null, isCueTrack = false, isUserAdded = false, playCount = 1, lastPlayedAt = null,
        firstSeenAt = now, durationMillis = 1000,
    )

    private class FakeAccess(val accessState: AccessState) : Access {
        override fun state() = accessState
        override fun requestPermission() = Unit
        override fun requestRescan() = Unit
        override fun openPlayer() = Unit
    }

    private class FakeLibrary(val failure: Throwable? = null, val tracks: List<PlayerTrack>) : Library {
        override suspend fun tracks(list: LibraryList, cutoff: Period, now: Instant): List<PlayerTrack> {
            failure?.let { throw it }
            return tracks
        }
    }

    private class RecordingHistory : PlayHistory {
        var recorded: List<PlayerTrack>? = null
        override suspend fun record(playerTracks: List<PlayerTrack>): List<PlayHistoryEntry> {
            recorded = playerTracks
            return emptyList()
        }

        override suspend fun exportNow() = Unit

        override suspend fun importFrom(json: String) = Unit
    }

    @Test
    fun recordsWhenThePlayerIsReady() = runTest {
        val history = RecordingHistory()

        HistorySnapshotter(FakeAccess(AccessState.Ready), FakeLibrary(tracks = listOf(track)), history, Clock.fixed(now, ZoneOffset.UTC)).snapshot()

        assertEquals(listOf(track), history.recorded)
    }

    @Test
    fun skipsWhenPermissionIsMissing() = runTest {
        val history = RecordingHistory()

        HistorySnapshotter(FakeAccess(AccessState.NeedsPermission), FakeLibrary(tracks = listOf(track)), history, Clock.fixed(now, ZoneOffset.UTC)).snapshot()

        assertEquals(null, history.recorded)
    }

    @Test
    fun skipsWhenAccessIsRevokedMidRead() = runTest {
        val history = RecordingHistory()
        val library = FakeLibrary(failure = SecurityException("revoked"), tracks = emptyList())

        HistorySnapshotter(FakeAccess(AccessState.Ready), library, history, Clock.fixed(now, ZoneOffset.UTC)).snapshot()

        assertEquals(null, history.recorded)
    }
}
