package app.playsift.ui

import app.playsift.files.FileLocation
import app.playsift.player.HistorySummary
import app.playsift.player.PlayerTrack
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSummaryTest {
    private val earliest = Instant.parse("2020-01-01T00:00:00Z")
    private val history = HistorySummary(earliestFirstSeenAt = earliest, totalTracks = 10, unplayedTracks = 4)

    private fun track(id: Long, artist: String = "Artist", playCount: Int = 0, folderPath: String? = "primary/Music/") = PlayerTrack(
        id = id, title = "t$id", artist = artist, album = "b", fileName = "$id.mp3", folderPath = folderPath,
        filePath = null, isCueTrack = false, isUserAdded = false, playCount = playCount, lastPlayedAt = null,
        firstSeenAt = earliest, durationMillis = 1000,
    )

    private fun location(id: Long) = FileLocation(relativePath = "Music/", displayName = "$id.mp3")

    @Test
    fun homeSummaryCountsAndSizes() {
        val allTracks = (1L..4L).map { track(it) }
        val summary = homeSummary(
            history = history,
            mostPlayed = listOf(track(1, playCount = 9)),
            deleteCandidates = listOf(allTracks[1], allTracks[2]),
            allTracks = allTracks,
            sizes = mapOf(location(1) to 1_000L, location(2) to 6_000_000L, location(3) to 7_000_000L, location(4) to 2_000L),
        )

        assertEquals(10, summary.totalSongs)
        assertEquals(6, summary.playedSongs)
        assertEquals(4, summary.neverPlayedSongs)
        assertEquals(2, summary.deleteCandidates)
        assertEquals(13_000_000L, summary.deleteCandidatesSizeBytes)
        assertEquals(13_003_000L, summary.librarySizeBytes)
        assertEquals(2, summary.largeSongs)
        assertEquals(13_000_000L, summary.largeSongsSizeBytes)
        assertEquals(1L, summary.mostPlayed?.id)
        assertEquals(earliest, summary.historySince)
    }

    @Test
    fun homeSummaryWithoutFileAccessHasNoSizes() {
        val summary = homeSummary(history, emptyList(), emptyList(), listOf(track(1)), sizes = null)

        assertEquals(10, summary.totalSongs)
        assertNull(summary.librarySizeBytes)
        assertNull(summary.deleteCandidatesSizeBytes)
        assertNull(summary.largeSongs)
        assertNull(summary.largeSongsSizeBytes)
        assertNull(summary.mostPlayed)
        assertNull(summary.topArtist)
    }

    @Test
    fun topArtistSumsPlays() {
        val mostPlayed = listOf(
            track(1, artist = "Solo", playCount = 8),
            track(2, artist = "Duo", playCount = 5),
            track(3, artist = "Duo", playCount = 4),
        )

        val summary = homeSummary(history, mostPlayed, emptyList(), emptyList(), sizes = null)

        assertEquals(ArtistPlays("Duo", 9), summary.topArtist)
    }

    @Test
    fun topArtistTiesGoToAlphabeticallyFirst() {
        val mostPlayed = listOf(track(1, artist = "Zed", playCount = 5), track(2, artist = "Abe", playCount = 5))

        val summary = homeSummary(history, mostPlayed, emptyList(), emptyList(), sizes = null)

        assertEquals("Abe", summary.topArtist?.artist)
    }

    @Test
    fun largeSongsKeepsFiveMegabytesAndUpBiggestFirst() {
        val allTracks = (1L..4L).map { track(it) }
        val sizes = mapOf(
            location(1) to 4_999_999L,
            location(2) to 5_000_000L,
            location(3) to 9_000_000L,
        )

        val large = largeSongs(allTracks, sizes)

        assertEquals(listOf(3L to 9_000_000L, 2L to 5_000_000L), large.map { (track, size) -> track.id to size })
    }
}
