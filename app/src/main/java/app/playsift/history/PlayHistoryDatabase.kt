package app.playsift.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.playsift.spotify.SpotifyDao
import app.playsift.spotify.SpotifyMatchEntry
import app.playsift.spotify.SpotifyPlaylistEntry
import java.time.Instant

/** The key gained `cueTitle`; SQLite cannot change a primary key in place, so the table is rebuilt with every row kept. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `play_history_new` (`folderPath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`artist` TEXT NOT NULL, `album` TEXT NOT NULL, `lastSeenPlayerCount` INTEGER NOT NULL, " +
                "`carriedPlays` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastPlayedAt` INTEGER, " +
                "`updatedAt` INTEGER NOT NULL, `cueTitle` TEXT NOT NULL, PRIMARY KEY(`folderPath`, `fileName`, `cueTitle`))",
        )
        db.execSQL(
            "INSERT INTO `play_history_new` SELECT `folderPath`, `fileName`, `title`, `artist`, `album`, `lastSeenPlayerCount`, " +
                "`carriedPlays`, `firstSeenAt`, `lastPlayedAt`, `updatedAt`, '' FROM `play_history`",
        )
        db.execSQL("DROP TABLE `play_history`")
        db.execSQL("ALTER TABLE `play_history_new` RENAME TO `play_history`")
    }
}

/** Adds the Spotify match cache and the remembered playlists; nothing existing changes. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `spotify_match` (`folderPath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `cueTitle` TEXT NOT NULL, " +
                "`spotifyUri` TEXT NOT NULL, `spotifyTitle` TEXT NOT NULL, `spotifyArtist` TEXT NOT NULL, " +
                "`isConfirmedByUser` INTEGER NOT NULL, `matchedAt` INTEGER NOT NULL, PRIMARY KEY(`folderPath`, `fileName`, `cueTitle`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `spotify_playlist` (`sourceKey` TEXT NOT NULL, `playlistId` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, PRIMARY KEY(`sourceKey`))",
        )
    }
}

class InstantConverters {
    @TypeConverter
    fun toEpochMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromEpochMillis(epochMillis: Long?): Instant? = epochMillis?.let(Instant::ofEpochMilli)
}

@Database(entities = [PlayHistoryEntry::class, SpotifyMatchEntry::class, SpotifyPlaylistEntry::class], version = 3)
@TypeConverters(InstantConverters::class)
abstract class PlayHistoryDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun spotifyDao(): SpotifyDao

    companion object {
        const val FILE_NAME = "play_history.db"

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        @Volatile
        private var instance: PlayHistoryDatabase? = null

        /** One shared instance per process, so the screen and the background worker never open the file twice. */
        fun open(context: Context): PlayHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** Truncate journaling keeps everything in the one file that Android auto backup copies. */
        private fun build(applicationContext: Context): PlayHistoryDatabase =
            Room.databaseBuilder(applicationContext, PlayHistoryDatabase::class.java, FILE_NAME)
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
