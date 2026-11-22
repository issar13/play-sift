package app.playsift.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

class InstantConverters {
    @TypeConverter
    fun toEpochMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromEpochMillis(epochMillis: Long?): Instant? = epochMillis?.let(Instant::ofEpochMilli)
}

@Database(entities = [PlayHistoryEntry::class], version = 2)
@TypeConverters(InstantConverters::class)
abstract class PlayHistoryDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao

    companion object {
        const val FILE_NAME = "play_history.db"

        val MIGRATIONS = arrayOf(MIGRATION_1_2)

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
