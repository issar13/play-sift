package app.playsift.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant

class InstantConverters {
    @TypeConverter
    fun toEpochMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromEpochMillis(epochMillis: Long?): Instant? = epochMillis?.let(Instant::ofEpochMilli)
}

@Database(entities = [PlayHistoryEntry::class], version = 1)
@TypeConverters(InstantConverters::class)
abstract class PlayHistoryDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao

    companion object {
        const val FILE_NAME = "play_history.db"

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
                .build()
    }
}
