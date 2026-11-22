package app.playsift.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.time.Instant
import java.time.Period
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Serves `/files` (as `folder_files`, with artist and album directly on the row) and `/folders`
 * from an in-memory SQLite database, running the caller's selection and sort order for real.
 */
class FakePlayerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.lastPathSegment == "folders") folderQueryCount += 1
        if (throwSecurityException) throw SecurityException("no data permission")
        if (returnNullCursor) return null
        val table = if (uri.lastPathSegment == "folders") "folders" else "folder_files"
        return database.query("$table AS $table", projection, selection, selectionArgs, null, null, sortOrder, uri.getQueryParameter("lim"))
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        var throwSecurityException = false
        var returnNullCursor = false
        var folderQueryCount = 0
        lateinit var database: SQLiteDatabase
    }
}

@RunWith(RobolectricTestRunner::class)
class PlayerLibraryTest {
    private val now = Instant.parse("2026-11-21T00:00:00Z")
    private val oneYearAgoSeconds = 1763683200L

    private lateinit var library: PlayerLibrary

    @Before
    fun setUp() {
        FakePlayerProvider.throwSecurityException = false
        FakePlayerProvider.returnNullCursor = false
        FakePlayerProvider.folderQueryCount = 0
        FakePlayerProvider.database = SQLiteDatabase.create(null)
        FakePlayerProvider.database.apply {
            execSQL("CREATE TABLE folders (_id INTEGER PRIMARY KEY, path TEXT, is_cue INTEGER)")
            execSQL(
                "CREATE TABLE folder_files (_id INTEGER PRIMARY KEY, title_tag TEXT, artist TEXT, album TEXT, " +
                    "name TEXT, folder_id INTEGER, file_path TEXT, cue_folder_id INTEGER, user_added INTEGER, " +
                    "played_times INTEGER, total_played_times INTEGER, played_at INTEGER, created_at INTEGER, " +
                    "duration INTEGER, offset_ms INTEGER)",
            )
            execSQL("INSERT INTO folders VALUES (1, 'primary/Music/Rock/', 0), (2, 'primary/Music/Pop/', 0), (3, 'primary/Music/Album.flac.cue/', 1)")
            insertFile(id = 1, name = "a.mp3", folderId = 1, totalPlays = 3, playedAt = 1_700_000_000_000L, createdAt = 1_704_067_200L - 31_536_000L)
            insertFile(id = 2, name = "b.mp3", folderId = 1, totalPlays = 0, createdAt = 1_704_067_200L)
            insertFile(id = 3, name = "c.mp3", folderId = 2, totalPlays = 0, createdAt = oneYearAgoSeconds)
            insertFile(id = 4, name = "d.mp3", folderId = 2, totalPlays = 0, createdAt = 1_795_000_000L)
        }
        Robolectric.buildContentProvider(FakePlayerProvider::class.java).create("com.maxmpz.audioplayer.data")
        library = PlayerLibrary(RuntimeEnvironment.getApplication().contentResolver)
    }

    private fun insertFile(
        id: Long,
        name: String,
        folderId: Long,
        totalPlays: Int,
        createdAt: Long,
        countedPlays: Int = 0,
        playedAt: Long = 0,
        cueFolderId: Long? = null,
        offsetMs: Long = 0,
    ) {
        FakePlayerProvider.database.execSQL(
            "INSERT INTO folder_files (_id, title_tag, artist, album, name, folder_id, file_path, cue_folder_id, " +
                "user_added, played_times, total_played_times, played_at, created_at, duration, offset_ms) " +
                "VALUES (?, ?, 'Artist', 'Album', ?, ?, ?, ?, 0, ?, ?, ?, ?, 180000, ?)",
            arrayOf<Any?>(id, "Title $name", name, folderId, "primary/Music/$name", cueFolderId, countedPlays, totalPlays, playedAt, createdAt, offsetMs),
        )
    }

    private suspend fun names(list: LibraryList) = library.tracks(list, Period.ofYears(1), now).map { it.fileName }

    private suspend fun isCueTrackByName(fileName: String) =
        library.tracks(LibraryList.NeverPlayed, Period.ofYears(1), now).first { it.fileName == fileName }.isCueTrack

    @Test
    fun trackInCueFolderIsNotRemovable() = runTest {
        insertFile(id = 20, name = "cue-part.flac", folderId = 3, totalPlays = 0, createdAt = 1_000L)

        assertTrue(isCueTrackByName("cue-part.flac"))
    }

    @Test
    fun trackWithOffsetIsNotRemovable() = runTest {
        insertFile(id = 21, name = "offset.flac", folderId = 1, totalPlays = 0, createdAt = 1_000L, offsetMs = 5_000L)

        assertTrue(isCueTrackByName("offset.flac"))
    }

    @Test
    fun cueSourceImageIsNotRemovable() = runTest {
        insertFile(id = 22, name = "image.flac", folderId = 1, totalPlays = 0, createdAt = 1_000L, cueFolderId = 3L)

        assertTrue(isCueTrackByName("image.flac"))
    }

    @Test
    fun ordinaryTrackIsNotACueTrack() = runTest {
        assertEquals(false, isCueTrackByName("b.mp3"))
    }

    @Test
    fun mostPlayedReturnsOnlyPlayedTracks() = runTest {
        assertEquals(listOf("a.mp3"), names(LibraryList.MostPlayed))
    }

    @Test
    fun neverPlayedReturnsOldestFirst() = runTest {
        assertEquals(listOf("b.mp3", "c.mp3", "d.mp3"), names(LibraryList.NeverPlayed))
    }

    @Test
    fun deleteCandidatesExcludeBoundaryAndRecent() = runTest {
        assertEquals(listOf("b.mp3"), names(LibraryList.DeleteCandidates))
    }

    @Test
    fun deleteCandidatesUsesTotalPlaysNotCountedPlays() = runTest {
        insertFile(id = 5, name = "e.mp3", folderId = 1, totalPlays = 2, createdAt = 1_000L, countedPlays = 0)
        insertFile(id = 6, name = "f.mp3", folderId = 1, totalPlays = 0, createdAt = 1_000L, countedPlays = 5)

        assertEquals(listOf("f.mp3", "b.mp3"), names(LibraryList.DeleteCandidates))
    }

    @Test
    fun mapsColumnsAndFolderPaths() = runTest {
        val track = library.tracks(LibraryList.MostPlayed, Period.ofYears(1), now).single()

        assertEquals("Title a.mp3", track.title)
        assertEquals("primary/Music/Rock/", track.folderPath)
        assertEquals(3, track.playCount)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), track.lastPlayedAt)
        assertEquals(Instant.ofEpochSecond(1_672_531_200L), track.firstSeenAt)
        assertEquals(180_000L, track.durationMillis)
    }

    @Test
    fun neverPlayedTracksHaveNoLastPlayedAt() = runTest {
        val track = library.tracks(LibraryList.NeverPlayed, Period.ofYears(1), now).first()

        assertNull(track.lastPlayedAt)
    }

    @Test
    fun securityExceptionPropagates() = runTest {
        FakePlayerProvider.throwSecurityException = true

        val failure = runCatching { library.tracks(LibraryList.MostPlayed, Period.ofYears(1), now) }.exceptionOrNull()

        assertEquals(SecurityException::class, failure!!::class)
    }

    @Test
    fun folderPathsResolvedForMoreThan999Folders() = runTest {
        val folderCount = 1200
        FakePlayerProvider.database.beginTransaction()
        (1..folderCount).forEach { number ->
            FakePlayerProvider.database.execSQL("INSERT INTO folders VALUES (?, ?, 0)", arrayOf<Any>(1000 + number, "primary/Many/$number/"))
            insertFile(id = 1000L + number, name = "many$number.mp3", folderId = 1000L + number, totalPlays = 1, createdAt = 1_000L)
        }
        FakePlayerProvider.database.setTransactionSuccessful()
        FakePlayerProvider.database.endTransaction()

        val tracks = library.tracks(LibraryList.MostPlayed, Period.ofYears(1), now)
            .filter { it.fileName.startsWith("many") }

        assertEquals(folderCount, tracks.size)
        assertEquals((1..folderCount).map { "primary/Many/$it/" }.toSet(), tracks.map { it.folderPath }.toSet())
        assertTrue(FakePlayerProvider.folderQueryCount > 1)
    }
}
