package app.playsift.files

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FileSizesTest {
    private lateinit var fileSizes: MediaStoreFileSizes

    @Before
    fun setUp() {
        FakeMediaStoreProvider.queryCount = 0
        FakeMediaStoreProvider.database = SQLiteDatabase.create(null)
        FakeMediaStoreProvider.database.apply {
            execSQL("CREATE TABLE audio (_id INTEGER PRIMARY KEY, relative_path TEXT, _display_name TEXT, _size INTEGER)")
            execSQL("INSERT INTO audio VALUES (10, 'Music/Rock/', 'a.mp3', 4000)")
            execSQL("INSERT INTO audio VALUES (11, 'Music/Rock/', 'dup.mp3', 1)")
            execSQL("INSERT INTO audio VALUES (12, 'Music/Rock/', 'dup.mp3', 2)")
            execSQL("INSERT INTO audio VALUES (13, 'Music/Pop/', 'b.mp3', 6000000)")
        }
        Robolectric.buildContentProvider(FakeMediaStoreProvider::class.java).create("media")
        fileSizes = MediaStoreFileSizes(RuntimeEnvironment.getApplication().contentResolver)
    }

    @After
    fun tearDown() {
        FakeMediaStoreProvider.database.close()
    }

    @Test
    fun mediaStoreFileSizesReadsOneQuery() = runTest {
        val sizes = fileSizes.sizesByLocation()

        assertEquals(1, FakeMediaStoreProvider.queryCount)
        assertEquals(4_000L, sizes[FileLocation("Music/Rock/", "a.mp3")])
        assertEquals(6_000_000L, sizes[FileLocation("Music/Pop/", "b.mp3")])
    }

    @Test
    fun duplicateLocationsGetNoSize() = runTest {
        val sizes = fileSizes.sizesByLocation()

        assertEquals(setOf(FileLocation("Music/Rock/", "a.mp3"), FileLocation("Music/Pop/", "b.mp3")), sizes.keys)
    }
}
