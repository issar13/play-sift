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

/**
 * Matches now record which local recording they were made for, and playlists are kept per Spotify account.
 * Old matches have no such record and are only a cache, so they are dropped and found again; old playlists
 * keep an empty account id and are given to the account that sends first.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE `spotify_match`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `spotify_match` (`folderPath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `cueTitle` TEXT NOT NULL, " +
                "`spotifyUri` TEXT NOT NULL, `spotifyTitle` TEXT NOT NULL, `spotifyArtist` TEXT NOT NULL, " +
                "`localTitle` TEXT NOT NULL, `localArtist` TEXT NOT NULL, `localDurationMillis` INTEGER NOT NULL, " +
                "`isConfirmedByUser` INTEGER NOT NULL, `matchedAt` INTEGER NOT NULL, PRIMARY KEY(`folderPath`, `fileName`, `cueTitle`))",
        )
        db.execSQL(
            "CREATE TABLE `spotify_playlist_new` (`accountId` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `playlistId` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, PRIMARY KEY(`accountId`, `sourceKey`))",
        )
        db.execSQL("INSERT INTO `spotify_playlist_new` SELECT '', `sourceKey`, `playlistId`, `url` FROM `spotify_playlist`")
        db.execSQL("DROP TABLE `spotify_playlist`")
        db.execSQL("ALTER TABLE `spotify_playlist_new` RENAME TO `spotify_playlist`")
    }
}

/** Rows of songs Play Sift moved to the trash are marked, so a re-added song never takes one over. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `play_history` ADD COLUMN `trashedAt` INTEGER")
    }
}

class InstantConverters {
    @TypeConverter
    fun toEpochMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromEpochMillis(epochMillis: Long?): Instant? = epochMillis?.let(Instant::ofEpochMilli)
}

@Database(entities = [PlayHistoryEntry::class, SpotifyMatchEntry::class, SpotifyPlaylistEntry::class], version = 5)
@TypeConverters(InstantConverters::class)
abstract class PlayHistoryDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun spotifyDao(): SpotifyDao

    companion object {
        const val FILE_NAME = "play_history.db"

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

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
