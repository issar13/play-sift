package app.playsift.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Serves `/playlists` and `/playlists/<id>/files` from an in-memory SQLite database. */
class FakePlaylistProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        if (throwSecurityException) throw SecurityException("no data permission")
        return if (uri.lastPathSegment == "playlists") {
            database.query("playlists AS playlists", projection, selection, selectionArgs, null, null, sortOrder)
        } else {
            val playlistId = uri.pathSegments[1]
            database.query("playlist_entries AS playlist_entries", projection, "playlist_id = ?", arrayOf(playlistId), null, null, sortOrder)
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        var throwSecurityException = false
        lateinit var database: SQLiteDatabase
    }
}

@RunWith(RobolectricTestRunner::class)
class PlayerPlaylistLibraryTest {
    private lateinit var library: PlayerPlaylistLibrary

    @Before
    fun setUp() {
        FakePlaylistProvider.throwSecurityException = false
        FakePlaylistProvider.database = SQLiteDatabase.create(null)
        FakePlaylistProvider.database.apply {
            execSQL("CREATE TABLE playlists (_id INTEGER PRIMARY KEY, playlist TEXT, num_files INTEGER)")
            execSQL("CREATE TABLE playlist_entries (_id INTEGER PRIMARY KEY, playlist_id INTEGER, folder_file_id INTEGER, sort INTEGER)")
            execSQL("INSERT INTO playlists VALUES (1, 'workout', 3), (2, 'Ambient', 0), (3, 'Chill', 1)")
            execSQL("INSERT INTO playlist_entries (playlist_id, folder_file_id, sort) VALUES (1, 30, 2), (1, 10, 0), (1, 20, 1), (3, 99, 0)")
        }
        Robolectric.buildContentProvider(FakePlaylistProvider::class.java).create("com.maxmpz.audioplayer.data")
        library = PlayerPlaylistLibrary(RuntimeEnvironment.getApplication().contentResolver)
    }

    @Test
    fun playlistsAreSortedByNameWithoutRegardToCase() = runTest {
        assertEquals(
            listOf(PlayerPlaylist(2, "Ambient", 0), PlayerPlaylist(3, "Chill", 1), PlayerPlaylist(1, "workout", 3)),
            library.playlists(),
        )
    }

    @Test
    fun songIdsComeInSortOrder() = runTest {
        assertEquals(listOf(10L, 20L, 30L), library.songIds(1))
    }

    @Test
    fun aMissingDataPermissionPropagates() = runTest {
        FakePlaylistProvider.throwSecurityException = true

        assertThrows(SecurityException::class.java) { kotlinx.coroutines.runBlocking { library.playlists() } }
        assertThrows(SecurityException::class.java) { kotlinx.coroutines.runBlocking { library.songIds(1) } }
    }
}
