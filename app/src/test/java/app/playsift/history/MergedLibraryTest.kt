package app.playsift.history

import app.playsift.player.LibraryList
import app.playsift.player.PlayerTrack
import java.time.Instant
import java.time.Period
import org.junit.Assert.assertEquals
import org.junit.Test

class MergedLibraryTest {
    private val earlier = Instant.parse("2015-01-01T00:00:00Z")
    private val playerFirstSeen = Instant.parse("2025-10-09T00:00:00Z")
    private val now = Instant.parse("2026-11-21T00:00:00Z")

    private fun track(id: Long, playCount: Int = 0, firstSeenAt: Instant = playerFirstSeen, title: String = "Song $id") = PlayerTrack(
        id = id, title = title, artist = "Artist", album = "Album", fileName = "$id.mp3", folderPath = "primary/Music/",
        filePath = null, isCueTrack = false, isUserAdded = false, playCount = playCount, lastPlayedAt = null,
        firstSeenAt = firstSeenAt, durationMillis = 1000,
    )

    private fun entry(id: Long, carriedPlays: Int = 0, lastSeenPlayerCount: Int = 0, firstSeenAt: Instant = playerFirstSeen) = PlayHistoryEntry(
        folderPath = "primary/Music/", fileName = "$id.mp3", title = "Song $id", artist = "Artist", album = "Album",
        lastSeenPlayerCount = lastSeenPlayerCount, carriedPlays = carriedPlays, firstSeenAt = firstSeenAt,
        lastPlayedAt = null, updatedAt = now,
    )

    @Test
    fun mergeUsesTotalPlaysAndStoredFirstSeen() {
        val merged = mergeHistory(listOf(track(1, playCount = 2)), listOf(entry(1, carriedPlays = 40, lastSeenPlayerCount = 2, firstSeenAt = earlier)))

        assertEquals(42, merged.single().playCount)
        assertEquals(earlier, merged.single().firstSeenAt)
    }

    @Test
    fun deleteCandidatesUseTotalPlays() {
        val oldUnplayedAtPlayer = track(1, firstSeenAt = Instant.parse("2020-01-01T00:00:00Z"))
        val oldUnplayedForGood = track(2, firstSeenAt = Instant.parse("2020-01-01T00:00:00Z"))
        val entries = listOf(entry(1, carriedPlays = 30, firstSeenAt = earlier), entry(2, firstSeenAt = earlier))

        val candidates = tracksFor(LibraryList.DeleteCandidates, mergeHistory(listOf(oldUnplayedAtPlayer, oldUnplayedForGood), entries), Period.ofYears(1), now)

        assertEquals(listOf(2L), candidates.map { it.id })
    }

    @Test
    fun deleteCandidatesUseStoredFirstSeen() {
        val recentAtPlayerButOldInHistory = track(1, firstSeenAt = Instant.parse("2026-10-01T00:00:00Z"))

        val candidates = tracksFor(LibraryList.DeleteCandidates, mergeHistory(listOf(recentAtPlayerButOldInHistory), listOf(entry(1, firstSeenAt = earlier))), Period.ofYears(1), now)

        assertEquals(listOf(1L), candidates.map { it.id })
    }

    @Test
    fun deleteCandidatesExcludeSongsInsideTheCutoff() {
        val recent = track(1, firstSeenAt = Instant.parse("2026-10-01T00:00:00Z"))

        assertEquals(emptyList<Long>(), tracksFor(LibraryList.DeleteCandidates, mergeHistory(listOf(recent), listOf(entry(1, firstSeenAt = recent.firstSeenAt))), Period.ofYears(1), now).map { it.id })
    }

    @Test
    fun mostPlayedSortsByTotalPlaysAndSkipsUnplayed() {
        val tracks = listOf(track(1, playCount = 5), track(2, playCount = 0), track(3, playCount = 1))
        val entries = listOf(entry(1, lastSeenPlayerCount = 5), entry(2, carriedPlays = 50), entry(3, lastSeenPlayerCount = 1))

        val mostPlayed = tracksFor(LibraryList.MostPlayed, mergeHistory(tracks, entries), Period.ofYears(1), now)

        assertEquals(listOf(2L, 1L, 3L), mostPlayed.map { it.id })
    }

    @Test
    fun neverPlayedListsOldestFirst() {
        val tracks = listOf(track(1, firstSeenAt = Instant.parse("2024-01-01T00:00:00Z")), track(2, firstSeenAt = Instant.parse("2022-01-01T00:00:00Z")), track(3, playCount = 4))
        val entries = tracks.map { entry(it.id, lastSeenPlayerCount = it.playCount, firstSeenAt = it.firstSeenAt) }

        assertEquals(listOf(2L, 1L), tracksFor(LibraryList.NeverPlayed, mergeHistory(tracks, entries), Period.ofYears(1), now).map { it.id })
    }

    @Test
    fun summaryCountsTotalsAndEarliestStoredDate() {
        val tracks = listOf(track(1), track(2))
        val entries = listOf(entry(1, carriedPlays = 3, firstSeenAt = earlier), entry(2), entry(99, firstSeenAt = Instant.parse("2012-01-01T00:00:00Z")))

        val summary = historySummary(mergeHistory(tracks, entries), entries)

        assertEquals(2, summary.totalTracks)
        assertEquals(1, summary.unplayedTracks)
        assertEquals(Instant.parse("2012-01-01T00:00:00Z"), summary.earliestFirstSeenAt)
    }
}
