package app.playsift.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerContractTest {
    @Test
    fun filesUriPointsAtPlayerFilesTable() {
        assertEquals("content://com.maxmpz.audioplayer.data/files", PlayerContract.FILES_URI.toString())
    }

    @Test
    fun foldersUriPointsAtPlayerFoldersTable() {
        assertEquals("content://com.maxmpz.audioplayer.data/folders", PlayerContract.FOLDERS_URI.toString())
    }

    @Test
    fun playCountColumnIsTotalPlayedTimes() {
        assertEquals("folder_files.total_played_times", PlayerContract.Columns.TOTAL_PLAYED_TIMES)
    }

    @Test
    fun createdAtColumnIsQualifiedByTable() {
        assertEquals("folder_files.created_at", PlayerContract.Columns.CREATED_AT)
    }
}
