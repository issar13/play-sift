package app.playsift.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.playsift.player.PlayerTrack
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayHistoryDaoTest {
    private val earlier = Instant.parse("2025-10-09T00:00:00Z")
    private val firstRun = Instant.parse("2026-11-20T00:00:00Z")
    private val secondRun = Instant.parse("2026-11-21T00:00:00Z")
    private lateinit var database: PlayHistoryDatabase

    private fun track(fileName: String, playCount: Int, title: String = fileName) = PlayerTrack(
        id = 1, title = title, artist = "Artist", album = "Album", fileName = fileName, folderPath = "primary/Music/",
        filePath = null, isCueTrack = false, isUserAdded = false, playCount = playCount,
        lastPlayedAt = if (playCount > 0) firstRun else null, firstSeenAt = earlier, durationMillis = 1000,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PlayHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun snapshotRoundTripsThroughRoom() = runTest {
        val dao = database.playHistoryDao()

        val returned = dao.record(listOf(track("a.mp3", playCount = 5), track("b.mp3", playCount = 0)), firstRun)

        assertEquals(returned.toSet(), dao.all().toSet())
        assertEquals(5, dao.all().single { it.fileName == "a.mp3" }.lastSeenPlayerCount)
        assertEquals(earlier, dao.all().first().firstSeenAt)
    }

    @Test
    fun secondSnapshotDetectsAPlayerResetFromTheStoredRow() = runTest {
        val dao = database.playHistoryDao()
        dao.record(listOf(track("a.mp3", playCount = 5)), firstRun)

        val after = dao.record(listOf(track("a.mp3", playCount = 1)), secondRun)

        assertEquals(6, after.single().totalPlays)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun relinkedSongReplacesTheOldRow() = runTest {
        val dao = database.playHistoryDao()
        dao.record(listOf(track("old.mp3", playCount = 5, title = "Same")), firstRun)

        dao.record(listOf(track("new.mp3", playCount = 5, title = "Same")), secondRun)

        assertEquals(listOf("new.mp3"), dao.all().map { it.fileName })
    }
}
