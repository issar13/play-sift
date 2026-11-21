package app.playsift.ui

import app.playsift.player.PlayerTrack
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayGroupsTest {
    private fun trackWithPlays(id: Long, plays: Int) = PlayerTrack(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        fileName = "song-$id.mp3",
        folderPath = null,
        filePath = null,
        isCueTrack = false,
        isUserAdded = false,
        playCount = plays,
        lastPlayedAt = null,
        firstSeenAt = Instant.parse("2024-01-01T00:00:00Z"),
        durationMillis = 200_000,
    )

    private fun tracksWithPlays(plays: Int, count: Int, firstId: Long) =
        (0 until count).map { offset -> trackWithPlays(firstId + offset, plays) }

    @Test
    fun groupsSixOrMoreWithSamePlays() {
        val tracks = listOf(trackWithPlays(100, 20)) + tracksWithPlays(plays = 12, count = 6, firstId = 1)
        val entries = groupByMatchingPlays(tracks)
        assertEquals(2, entries.size)
        assertEquals(MostPlayedEntry.Single(tracks.first()), entries[0])
        assertEquals(MostPlayedEntry.Group(plays = 12, tracks = tracks.drop(1)), entries[1])
    }

    @Test
    fun groupAtVeryTopOfListIsGrouped() {
        val tracks = tracksWithPlays(plays = 9, count = 6, firstId = 1) + trackWithPlays(100, 2)
        val entries = groupByMatchingPlays(tracks)
        assertEquals(listOf(MostPlayedEntry.Group(9, tracks.take(6)), MostPlayedEntry.Single(tracks.last())), entries)
    }

    @Test
    fun fiveWithSamePlaysStayPlain() {
        val tracks = tracksWithPlays(plays = 4, count = 5, firstId = 1)
        assertEquals(tracks.map { track -> MostPlayedEntry.Single(track) }, groupByMatchingPlays(tracks))
    }

    @Test
    fun keepsPlaysDescendingOrder() {
        val tracks = tracksWithPlays(10, 6, 1) + trackWithPlays(50, 8) + tracksWithPlays(5, 7, 60)
        val entries = groupByMatchingPlays(tracks)
        val playsInOrder = entries.map { entry ->
            when (entry) {
                is MostPlayedEntry.Single -> entry.track.playCount
                is MostPlayedEntry.Group -> entry.plays
            }
        }
        assertEquals(listOf(10, 8, 5), playsInOrder)
    }

    @Test
    fun emptyListGivesNoEntries() {
        assertEquals(emptyList<MostPlayedEntry>(), groupByMatchingPlays(emptyList()))
    }
}
