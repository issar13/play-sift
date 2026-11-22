package app.playsift.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
        database.close()

        assertEquals(listOf("a.mp3", "b.mp3"), rows.map { it.fileName })
        assertEquals(listOf("", ""), rows.map { it.cueTitle })
        assertEquals(7, rows.first().totalPlays)
        assertEquals(Instant.ofEpochMilli(3000), rows.last().lastPlayedAt)
    }
}
