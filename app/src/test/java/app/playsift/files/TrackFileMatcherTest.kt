package app.playsift.files

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.playsift.player.PlayerTrack
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Serves MediaStore audio rows from an in-memory SQLite database, running the caller's selection for real. */
class FakeMediaStoreProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        queryCount += 1
        return database.query("audio", projection, selection, selectionArgs, null, null, sortOrder)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        var queryCount = 0
        lateinit var database: SQLiteDatabase
    }
}

@RunWith(RobolectricTestRunner::class)
class TrackFileMatcherTest {
    private lateinit var matcher: TrackFileMatcher

    @Before
    fun setUp() {
        FakeMediaStoreProvider.queryCount = 0
        FakeMediaStoreProvider.database = SQLiteDatabase.create(null)
        FakeMediaStoreProvider.database.apply {
            execSQL("CREATE TABLE audio (_id INTEGER PRIMARY KEY, relative_path TEXT, _display_name TEXT, _size INTEGER)")
            insertAudio(id = 10, relativePath = "Music/Rock/", displayName = "a.mp3", sizeBytes = 4_000L)
            insertAudio(id = 11, relativePath = "Music/Rock/", displayName = "dup.mp3", sizeBytes = 1L)
            insertAudio(id = 12, relativePath = "Music/Rock/", displayName = "dup.mp3", sizeBytes = 2L)
            insertAudio(id = 13, relativePath = "Music/Rock/", displayName = "Don't Stop.mp3", sizeBytes = 5_000L)
        }
        Robolectric.buildContentProvider(FakeMediaStoreProvider::class.java).create("media")
        matcher = TrackFileMatcher(RuntimeEnvironment.getApplication().contentResolver)
    }

    @After
    fun tearDown() {
        FakeMediaStoreProvider.database.close()
    }

    private fun insertAudio(id: Long, relativePath: String, displayName: String, sizeBytes: Long) {
        FakeMediaStoreProvider.database.execSQL(
            "INSERT INTO audio VALUES (?, ?, ?, ?)",
            arrayOf<Any>(id, relativePath, displayName, sizeBytes),
        )
    }

    private fun track(
        fileName: String,
        id: Long = 1,
        folderPath: String? = "primary/Music/Rock/",
        filePath: String? = null,
        isCueTrack: Boolean = false,
        isUserAdded: Boolean = false,
    ) = PlayerTrack(
        id = id,
        title = "Title",
        artist = "Artist",
        album = "Album",
        fileName = fileName,
        folderPath = folderPath,
        filePath = filePath,
        isCueTrack = isCueTrack,
        isUserAdded = isUserAdded,
        playCount = 0,
        lastPlayedAt = null,
        firstSeenAt = Instant.EPOCH,
        durationMillis = 1_000L,
    )

    @Test
    fun mediaStoreLocationStripsPrimaryVolume() {
        assertEquals("Music/Artist/", mediaStoreLocation("primary/Music/Artist/"))
    }

    @Test
    fun mediaStoreLocationRejectsSdCard() {
        assertNull(mediaStoreLocation("1234-ABCD/Music/"))
    }

    @Test
    fun singleMatchIsRemovableWithSize() = runTest {
        val file = matcher.match(listOf(track("a.mp3"))).single() as TrackFile.Removable

        assertEquals(4_000L, file.sizeBytes)
        assertEquals(10L, ContentUris.parseId(file.uri))
    }

    @Test
    fun twoTracksSharingOneFileAreNotRemovable() = runTest {
        val files = matcher.match(listOf(track("a.mp3", id = 1), track("a.mp3", id = 2), track("Don't Stop.mp3", id = 3)))

        assertEquals(NotRemovableReason.AmbiguousMatch, (files[0] as TrackFile.NotRemovable).reason)
        assertEquals(NotRemovableReason.AmbiguousMatch, (files[1] as TrackFile.NotRemovable).reason)
        assertEquals(5_000L, (files[2] as TrackFile.Removable).sizeBytes)
    }

    @Test
    fun sameFileNameInDifferentFolderIsNotMatched() = runTest {
        val file = matcher.match(listOf(track("a.mp3", folderPath = "primary/Music/Pop/"))).single() as TrackFile.NotRemovable

        assertEquals(NotRemovableReason.NotFoundOnPhone, file.reason)
    }

    @Test
    fun noMatchIsNotFound() = runTest {
        val file = matcher.match(listOf(track("missing.mp3"))).single()

        assertEquals(TrackFile.NotRemovable(track("missing.mp3"), NotRemovableReason.NotFoundOnPhone), file)
    }

    @Test
    fun duplicateMatchIsAmbiguous() = runTest {
        val file = matcher.match(listOf(track("dup.mp3"))).single() as TrackFile.NotRemovable

        assertEquals(NotRemovableReason.AmbiguousMatch, file.reason)
    }

    @Test
    fun cueTrackSkippedWithoutQuery() = runTest {
        val file = matcher.match(listOf(track("a.mp3", isCueTrack = true))).single() as TrackFile.NotRemovable

        assertEquals(NotRemovableReason.CueTrack, file.reason)
        assertEquals(0, FakeMediaStoreProvider.queryCount)
    }

    @Test
    fun streamTrackSkipped() = runTest {
        val streamed = matcher.match(listOf(track("a.mp3", filePath = "https://example.com/a.mp3")))
        val userAdded = matcher.match(listOf(track("a.mp3", isUserAdded = true)))

        assertEquals(NotRemovableReason.StreamOrProvider, (streamed.single() as TrackFile.NotRemovable).reason)
        assertEquals(NotRemovableReason.StreamOrProvider, (userAdded.single() as TrackFile.NotRemovable).reason)
        assertEquals(0, FakeMediaStoreProvider.queryCount)
    }

    @Test
    fun sdCardTrackNotOnInternalStorage() = runTest {
        val sdCard = matcher.match(listOf(track("a.mp3", folderPath = "1234-ABCD/Music/Rock/")))
        val noFolder = matcher.match(listOf(track("a.mp3", folderPath = null)))

        assertEquals(NotRemovableReason.NotOnInternalStorage, (sdCard.single() as TrackFile.NotRemovable).reason)
        assertEquals(NotRemovableReason.NotOnInternalStorage, (noFolder.single() as TrackFile.NotRemovable).reason)
        assertEquals(0, FakeMediaStoreProvider.queryCount)
    }

    @Test
    fun apostropheInFileNameMatches() = runTest {
        val file = matcher.match(listOf(track("Don't Stop.mp3"))).single() as TrackFile.Removable

        assertEquals(5_000L, file.sizeBytes)
    }
}
