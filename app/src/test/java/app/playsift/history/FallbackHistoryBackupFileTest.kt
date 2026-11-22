package app.playsift.history

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeBackupStorage(
    var existing: String?,
    val lockedUris: Set<String> = emptySet(),
) : BackupStorage {
    val writes = mutableListOf<Pair<String, String>>()
    var createdCount = 0

    override suspend fun findExisting() = existing

    override suspend fun overwrite(uri: String, json: String) {
        if (uri in lockedUris) throw IOException("owned by an older install")
        writes += uri to json
    }

    override suspend fun create(): String = "content://files/new-${++createdCount}"
}

private class FakeLocationStore(var saved: String? = null) : BackupLocationStore {
    override suspend fun read() = saved

    override suspend fun save(uri: String) {
        saved = uri
    }
}

class FallbackHistoryBackupFileTest {
    @Test
    fun updatesTheExistingFileInPlace() = runTest {
        val storage = FakeBackupStorage(existing = "content://files/1")
        val locationStore = FakeLocationStore()

        FallbackHistoryBackupFile(storage, locationStore).write("{}")

        assertEquals(listOf("content://files/1" to "{}"), storage.writes)
        assertEquals(null, locationStore.saved)
    }

    @Test
    fun writesANewFileAndRemembersItWhenTheOldOneIsLocked() = runTest {
        val storage = FakeBackupStorage(existing = "content://files/1", lockedUris = setOf("content://files/1"))
        val locationStore = FakeLocationStore()

        FallbackHistoryBackupFile(storage, locationStore).write("{}")

        assertEquals(listOf("content://files/new-1" to "{}"), storage.writes)
        assertEquals("content://files/new-1", locationStore.saved)
    }

    @Test
    fun usesTheRememberedFileOnTheNextWrite() = runTest {
        val storage = FakeBackupStorage(existing = "content://files/1", lockedUris = setOf("content://files/1"))
        val locationStore = FakeLocationStore(saved = "content://files/new-1")

        FallbackHistoryBackupFile(storage, locationStore).write("{\"v\":2}")

        assertEquals(listOf("content://files/new-1" to "{\"v\":2}"), storage.writes)
        assertEquals(0, storage.createdCount)
    }

    @Test
    fun createsAFileWhenNoneExists() = runTest {
        val storage = FakeBackupStorage(existing = null)
        val locationStore = FakeLocationStore()

        FallbackHistoryBackupFile(storage, locationStore).write("{}")

        assertEquals("content://files/new-1", locationStore.saved)
    }
}
