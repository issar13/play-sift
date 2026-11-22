package app.playsift.history

import app.playsift.player.PlayerTrack
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistorySnapshotTest {
    private val earlier = Instant.parse("2025-10-09T00:00:00Z")
    private val later = Instant.parse("2026-11-01T00:00:00Z")
    private val now = Instant.parse("2026-11-21T00:00:00Z")

    private fun track(
        fileName: String = "song.mp3",
        title: String = "Song",
        artist: String = "Artist",
        playCount: Int = 0,
        firstSeenAt: Instant = earlier,
        lastPlayedAt: Instant? = null,
        id: Long = 1,
    ) = PlayerTrack(
        id = id, title = title, artist = artist, album = "Album", fileName = fileName, folderPath = "primary/Music/",
        filePath = null, isCueTrack = false, isUserAdded = false, playCount = playCount, lastPlayedAt = lastPlayedAt,
        firstSeenAt = firstSeenAt, durationMillis = 1000,
    )

    private fun entry(
        fileName: String = "song.mp3",
        title: String = "Song",
        artist: String = "Artist",
        lastSeenPlayerCount: Int = 0,
        carriedPlays: Int = 0,
        firstSeenAt: Instant = earlier,
        lastPlayedAt: Instant? = null,
    ) = PlayHistoryEntry(
        folderPath = "primary/Music/", fileName = fileName, title = title, artist = artist, album = "Album",
        lastSeenPlayerCount = lastSeenPlayerCount, carriedPlays = carriedPlays, firstSeenAt = firstSeenAt,
        lastPlayedAt = lastPlayedAt, updatedAt = earlier,
    )

    @Test
    fun cueTracksSharingOneFileKeepSeparateRows() {
        val firstCue = track(title = "Cue one", playCount = 3, id = 1).copy(isCueTrack = true)
        val secondCue = track(title = "Cue two", playCount = 7, id = 2).copy(isCueTrack = true)

        val entries = snapshotHistory(emptyList(), listOf(firstCue, secondCue), now).entries

        assertEquals(setOf("Cue one" to 3, "Cue two" to 7), entries.map { it.cueTitle to it.lastSeenPlayerCount }.toSet())
        assertEquals(setOf("Cue one", "Cue two"), entries.map { it.title }.toSet())
    }

    @Test
    fun ordinaryTrackKeyHasNoCueTitle() {
        assertEquals("", track().historyKey().cueTitle)
    }

    @Test
    fun newSongStartsRowWithPlayerCount() {
        val snapshot = snapshotHistory(emptyList(), listOf(track(playCount = 4, lastPlayedAt = later)), now)

        val row = snapshot.entries.single()
        assertEquals(4, row.lastSeenPlayerCount)
        assertEquals(0, row.carriedPlays)
        assertEquals(earlier, row.firstSeenAt)
        assertEquals(later, row.lastPlayedAt)
        assertEquals(now, row.updatedAt)
    }

    @Test
    fun resetAddsPreviousCountToCarriedPlays() {
        val stored = listOf(entry(lastSeenPlayerCount = 40, carriedPlays = 10))

        val row = snapshotHistory(stored, listOf(track(playCount = 2)), now).entries.single()

        assertEquals(50, row.carriedPlays)
        assertEquals(2, row.lastSeenPlayerCount)
        assertEquals(52, row.totalPlays)
    }

    @Test
    fun resetToZeroStillCarries() {
        val stored = listOf(entry(lastSeenPlayerCount = 7))

        val row = snapshotHistory(stored, listOf(track(playCount = 0)), now).entries.single()

        assertEquals(7, row.carriedPlays)
        assertEquals(7, row.totalPlays)
    }

    @Test
    fun normalIncreaseDoesNotCarry() {
        val stored = listOf(entry(lastSeenPlayerCount = 3, carriedPlays = 10))

        val row = snapshotHistory(stored, listOf(track(playCount = 5)), now).entries.single()

        assertEquals(10, row.carriedPlays)
        assertEquals(5, row.lastSeenPlayerCount)
        assertEquals(15, row.totalPlays)
    }

    @Test
    fun unchangedCountDoesNotCarry() {
        val stored = listOf(entry(lastSeenPlayerCount = 3))

        assertEquals(0, snapshotHistory(stored, listOf(track(playCount = 3)), now).entries.single().carriedPlays)
    }

    @Test
    fun firstSeenNeverMovesForward() {
        val stored = listOf(entry(firstSeenAt = earlier))

        val row = snapshotHistory(stored, listOf(track(firstSeenAt = later)), now).entries.single()

        assertEquals(earlier, row.firstSeenAt)
    }

    @Test
    fun firstSeenMovesBackwardWhenPlayerKnowsAnEarlierDate() {
        val stored = listOf(entry(firstSeenAt = later))

        assertEquals(earlier, snapshotHistory(stored, listOf(track(firstSeenAt = earlier)), now).entries.single().firstSeenAt)
    }

    @Test
    fun lastPlayedKeepsLatestEver() {
        val stored = listOf(entry(lastPlayedAt = later))

        val row = snapshotHistory(stored, listOf(track(lastPlayedAt = earlier)), now).entries.single()

        assertEquals(later, row.lastPlayedAt)
    }

    @Test
    fun lastPlayedStaysEmptyWhenNeverPlayed() {
        assertNull(snapshotHistory(emptyList(), listOf(track()), now).entries.single().lastPlayedAt)
    }

    @Test
    fun movedFileRelinksByTitleAndArtist() {
        val stored = listOf(entry(fileName = "old.mp3", lastSeenPlayerCount = 9, carriedPlays = 20, firstSeenAt = earlier))

        val snapshot = snapshotHistory(stored, listOf(track(fileName = "new.mp3", playCount = 9, firstSeenAt = later)), now)

        val row = snapshot.entries.single()
        assertEquals("new.mp3", row.fileName)
        assertEquals(29, row.totalPlays)
        assertEquals(earlier, row.firstSeenAt)
        assertEquals(listOf("old.mp3"), snapshot.removed.map { it.fileName })
    }

    @Test
    fun ambiguousTitleArtistStartsNewRow() {
        val stored = listOf(
            entry(fileName = "old1.mp3", carriedPlays = 5),
            entry(fileName = "old2.mp3", carriedPlays = 6),
        )

        val snapshot = snapshotHistory(stored, listOf(track(fileName = "new.mp3")), now)

        assertEquals(setOf("old1.mp3", "old2.mp3", "new.mp3"), snapshot.entries.map { it.fileName }.toSet())
        assertEquals(0, snapshot.entries.single { it.fileName == "new.mp3" }.carriedPlays)
        assertEquals(emptyList<PlayHistoryEntry>(), snapshot.removed)
    }

    @Test
    fun twoNewSongsWithTheSameTitleDoNotClaimOneStoredRow() {
        val stored = listOf(entry(fileName = "old.mp3", carriedPlays = 5))

        val snapshot = snapshotHistory(stored, listOf(track(fileName = "new1.mp3", id = 1), track(fileName = "new2.mp3", id = 2)), now)

        assertEquals(setOf("old.mp3", "new1.mp3", "new2.mp3"), snapshot.entries.map { it.fileName }.toSet())
        assertEquals(emptyList<PlayHistoryEntry>(), snapshot.removed)
    }

    @Test
    fun blankTitleNeverRelinks() {
        val stored = listOf(entry(fileName = "old.mp3", title = "", artist = "", carriedPlays = 5))

        val snapshot = snapshotHistory(stored, listOf(track(fileName = "new.mp3", title = "", artist = "")), now)

        assertEquals(2, snapshot.entries.size)
    }

    @Test
    fun songsMissingFromThePlayerKeepTheirRow() {
        val stored = listOf(entry(fileName = "trashed.mp3", carriedPlays = 12))

        val snapshot = snapshotHistory(stored, listOf(track(fileName = "other.mp3", title = "Other")), now)

        assertEquals(12, snapshot.entries.single { it.fileName == "trashed.mp3" }.carriedPlays)
    }

    @Test
    fun songsSharingOneFileUseTheHigherCount() {
        val snapshot = snapshotHistory(emptyList(), listOf(track(playCount = 2, id = 1), track(playCount = 6, id = 2)), now)

        assertEquals(6, snapshot.entries.single().lastSeenPlayerCount)
    }
}
