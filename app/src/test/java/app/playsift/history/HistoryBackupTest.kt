package app.playsift.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.playsift.player.PlayerTrack
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryBackupTest {
    private val earlier = Instant.parse("2015-01-01T00:00:00Z")
    private val later = Instant.parse("2026-11-01T00:00:00Z")
    private val now = Instant.parse("2026-11-21T00:00:00Z")

    private fun entry(
        fileName: String = "song.mp3",
        title: String = "Song",
        lastSeenPlayerCount: Int = 0,
        carriedPlays: Int = 0,
        firstSeenAt: Instant = later,
        lastPlayedAt: Instant? = null,
        updatedAt: Instant = later,
    ) = PlayHistoryEntry(
        folderPath = "primary/Music/", fileName = fileName, title = title, artist = "Artist", album = "Album",
        lastSeenPlayerCount = lastSeenPlayerCount, carriedPlays = carriedPlays, firstSeenAt = firstSeenAt,
        lastPlayedAt = lastPlayedAt, updatedAt = updatedAt,
    )

    @Test
    fun importMergesTakingLargerValues() {
        val existing = listOf(entry(lastSeenPlayerCount = 3, carriedPlays = 0, firstSeenAt = later, lastPlayedAt = earlier, title = "New title", updatedAt = now))
        val imported = listOf(entry(lastSeenPlayerCount = 50, carriedPlays = 100, firstSeenAt = earlier, lastPlayedAt = later, title = "Old title", updatedAt = earlier))

        val merged = mergeImported(existing, imported).single()

        assertEquals(50, merged.lastSeenPlayerCount)
        assertEquals(100, merged.carriedPlays)
        assertEquals(earlier, merged.firstSeenAt)
        assertEquals(later, merged.lastPlayedAt)
        assertEquals("New title", merged.title)
        assertEquals(now, merged.updatedAt)
    }

    @Test
    fun importKeepsLocalValuesThatAreLarger() {
        val existing = listOf(entry(lastSeenPlayerCount = 80, carriedPlays = 5))
        val imported = listOf(entry(lastSeenPlayerCount = 50, carriedPlays = 2))

        val merged = mergeImported(existing, imported).single()

        assertEquals(80, merged.lastSeenPlayerCount)
        assertEquals(5, merged.carriedPlays)
    }

    @Test
    fun importKeepsTheSideWithTheLargerTotalAsIs() {
        val existing = listOf(entry(lastSeenPlayerCount = 2, carriedPlays = 40))
        val imported = listOf(entry(lastSeenPlayerCount = 40, carriedPlays = 0))

        val merged = mergeImported(existing, imported).single()

        assertEquals(2, merged.lastSeenPlayerCount)
        assertEquals(40, merged.carriedPlays)
    }

    @Test
    fun importingTheSameFileAgainAfterAResetKeepsTheTotal() {
        val oldFile = listOf(entry(lastSeenPlayerCount = 40))
        val playerAfterReset = listOf(playerTrack(playCount = 2))
        val afterFirstImport = mergeImported(listOf(entry(lastSeenPlayerCount = 2)), oldFile)
        val afterSnapshot = snapshotHistory(afterFirstImport, playerAfterReset, now).entries

        val afterSecondImport = mergeImported(afterSnapshot, oldFile)
        val afterSecondSnapshot = snapshotHistory(afterSecondImport, playerAfterReset, now).entries
        val afterThirdImport = mergeImported(afterSecondSnapshot, oldFile)
        val afterThirdSnapshot = snapshotHistory(afterThirdImport, playerAfterReset, now).entries

        assertEquals(42, afterSnapshot.single().totalPlays)
        assertEquals(42, afterSecondSnapshot.single().totalPlays)
        assertEquals(42, afterThirdSnapshot.single().totalPlays)
    }

    private fun playerTrack(playCount: Int) = PlayerTrack(
        id = 1, title = "Song", artist = "Artist", album = "Album", fileName = "song.mp3", folderPath = "primary/Music/",
        filePath = null, isCueTrack = false, isUserAdded = false, playCount = playCount, lastPlayedAt = null,
        firstSeenAt = later, durationMillis = 1000,
    )

    @Test
    fun importAddsSongsThatAreOnlyInTheFile() {
        val merged = mergeImported(listOf(entry(fileName = "a.mp3")), listOf(entry(fileName = "b.mp3")))

        assertEquals(setOf("b.mp3"), merged.map { it.fileName }.toSet())
    }

    @Test
    fun importedTotalSurvivesTheNextResetCheck() {
        val existing = listOf(entry(lastSeenPlayerCount = 3))
        val imported = listOf(entry(lastSeenPlayerCount = 50, carriedPlays = 100))
        val mergedRow = mergeImported(existing, imported).single()

        assertEquals(150, mergedRow.totalPlays)
    }

    @Test
    fun exportThenImportGivesTheSameRows() = runTest {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PlayHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = database.playHistoryDao()
        val rows = listOf(
            entry(fileName = "a.mp3", carriedPlays = 7, lastSeenPlayerCount = 2, lastPlayedAt = later, firstSeenAt = earlier),
            entry(fileName = "b.mp3", title = "Quote \" and \\ slash"),
        )
        dao.upsert(rows)

        val json = encodeHistory(dao.all(), now)
        database.clearAllTables()
        dao.importEntries(decodeHistory(json))

        assertEquals(rows.toSet(), dao.all().toSet())
        database.close()
    }

    @Test
    fun decodeRejectsAnUnknownVersion() {
        assertThrows(IllegalArgumentException::class.java) { decodeHistory("""{"version":99,"entries":[]}""") }
    }

    private fun backupWithCounts(lastSeenPlayerCount: Long, carriedPlays: Long) =
        """{"version":1,"exportedAt":"2026-11-21T00:00:00Z","entries":[{"folderPath":"primary/Music/","fileName":"a.mp3","cueTitle":"","title":"A","artist":"B","album":"C","lastSeenPlayerCount":$lastSeenPlayerCount,"carriedPlays":$carriedPlays,"firstSeenAt":"2026-01-01T00:00:00Z","lastPlayedAt":null,"updatedAt":"2026-01-01T00:00:00Z"}]}"""

    @Test
    fun decodeRejectsNegativePlayCounts() {
        assertThrows(IllegalArgumentException::class.java) { decodeHistory(backupWithCounts(lastSeenPlayerCount = 5, carriedPlays = -5)) }
        assertThrows(IllegalArgumentException::class.java) { decodeHistory(backupWithCounts(lastSeenPlayerCount = -1, carriedPlays = 0)) }
    }

    @Test
    fun decodeRejectsCountsWhoseTotalDoesNotFitAnInt() {
        val almostFull = Int.MAX_VALUE.toLong()

        assertThrows(IllegalArgumentException::class.java) { decodeHistory(backupWithCounts(almostFull, 1)) }
    }

    @Test
    fun decodeAcceptsLargeCountsThatStillFit() {
        val entries = decodeHistory(backupWithCounts(lastSeenPlayerCount = 1_000, carriedPlays = Int.MAX_VALUE - 1_000L))

        assertEquals(Int.MAX_VALUE, entries.single().totalPlays)
    }

    @Test
    fun readingStopsWithAnErrorWhenTheFileIsOverTheLimit() {
        val oversized = ByteArray(11) { 'a'.code.toByte() }

        assertThrows(BackupTooLargeException::class.java) { readBackupText(oversized.inputStream(), maxBytes = 10) }
    }

    @Test
    fun aFileWithinTheLimitIsReadWhole() {
        val text = "{\"version\":1}"

        assertEquals(text, readBackupText(text.byteInputStream(), maxBytes = text.length))
    }
}
