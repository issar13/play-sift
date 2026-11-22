package app.playsift.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.playsift.spotify.SpotifyMatchEntry
import app.playsift.spotify.SpotifyPlaylistEntry
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayHistoryMigrationTest {
    @Test
    fun migrationFromVersionOneKeepsEveryRow() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseFile = context.getDatabasePath("migration-test.db").apply { parentFile!!.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { oldDatabase ->
            oldDatabase.execSQL(
                "CREATE TABLE `play_history` (`folderPath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`artist` TEXT NOT NULL, `album` TEXT NOT NULL, `lastSeenPlayerCount` INTEGER NOT NULL, " +
                    "`carriedPlays` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastPlayedAt` INTEGER, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`folderPath`, `fileName`))",
            )
            oldDatabase.execSQL("INSERT INTO play_history VALUES ('primary/Music/', 'a.mp3', 'A', 'Artist', 'Album', 5, 2, 1000, NULL, 2000)")
            oldDatabase.execSQL("INSERT INTO play_history VALUES ('primary/Music/', 'b.mp3', 'B', 'Artist', 'Album', 0, 0, 1000, 3000, 2000)")
            oldDatabase.version = 1
        }

        val database = Room.databaseBuilder(context, PlayHistoryDatabase::class.java, "migration-test.db")
            .addMigrations(*PlayHistoryDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val rows = database.playHistoryDao().all().sortedBy { it.fileName }
        val spotifyDao = database.spotifyDao()
        spotifyDao.upsertPlaylist(SpotifyPlaylistEntry("account", "top", "playlist-1", "https://open.spotify.com/playlist/playlist-1"))
        spotifyDao.upsertMatch(SpotifyMatchEntry("primary/Music/", "a.mp3", "", "spotify:track:1", "A", "Artist", "A", "Artist", 1000, false, Instant.ofEpochMilli(5000)))
        val storedPlaylist = spotifyDao.playlistFor("account", "top")
        val storedMatches = spotifyDao.allMatches()
        database.close()

        assertEquals("playlist-1", storedPlaylist?.playlistId)
        assertEquals(1, storedMatches.size)

        assertEquals(listOf("a.mp3", "b.mp3"), rows.map { it.fileName })
        assertEquals(listOf("", ""), rows.map { it.cueTitle })
        assertEquals(7, rows.first().totalPlays)
        assertEquals(Instant.ofEpochMilli(3000), rows.last().lastPlayedAt)
    }

    @Test
    fun migrationFromVersionFourKeepsRowsUntrashed() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseFile = context.getDatabasePath("migration-test-4.db").apply { parentFile!!.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { oldDatabase ->
            oldDatabase.execSQL(
                "CREATE TABLE `play_history` (`folderPath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`artist` TEXT NOT NULL, `album` TEXT NOT NULL, `lastSeenPlayerCount` INTEGER NOT NULL, " +
                    "`carriedPlays` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastPlayedAt` INTEGER, " +
                    "`updatedAt` INTEGER NOT NULL, `cueTitle` TEXT NOT NULL, PRIMARY KEY(`folderPath`, `fileName`, `cueTitle`))",
            )
            oldDatabase.execSQL("CREATE TABLE `spotify_match` (`folderPath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `cueTitle` TEXT NOT NULL, `spotifyUri` TEXT NOT NULL, `spotifyTitle` TEXT NOT NULL, `spotifyArtist` TEXT NOT NULL, `localTitle` TEXT NOT NULL, `localArtist` TEXT NOT NULL, `localDurationMillis` INTEGER NOT NULL, `isConfirmedByUser` INTEGER NOT NULL, `matchedAt` INTEGER NOT NULL, PRIMARY KEY(`folderPath`, `fileName`, `cueTitle`))")
            oldDatabase.execSQL("CREATE TABLE `spotify_playlist` (`accountId` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `playlistId` TEXT NOT NULL, `url` TEXT NOT NULL, PRIMARY KEY(`accountId`, `sourceKey`))")
            oldDatabase.execSQL("INSERT INTO play_history VALUES ('primary/Music/', 'a.mp3', 'A', 'Artist', 'Album', 5, 2, 1000, NULL, 2000, '')")
            oldDatabase.version = 4
        }

        val database = Room.databaseBuilder(context, PlayHistoryDatabase::class.java, "migration-test-4.db")
            .addMigrations(*PlayHistoryDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val dao = database.playHistoryDao()
        val before = dao.all().single()
        dao.markTrashed(listOf(HistoryKey("primary/Music/", "a.mp3", "")), Instant.ofEpochMilli(9000))
        val after = dao.all().single()
        database.close()

        assertEquals(null, before.trashedAt)
        assertEquals(7, before.totalPlays)
        assertEquals(Instant.ofEpochMilli(9000), after.trashedAt)
    }
}
