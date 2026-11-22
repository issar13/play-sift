package app.playsift.history

import app.playsift.spotify.failureOf
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeBackupStorage(var existing: String?) : BackupStorage {
    /** Complete files by uri; a published file is the backup. */
    val files = mutableMapOf<String, String>()
    val published = mutableListOf<Pair<String, String?>>()
    var temporaryFailure: Exception? = null
    var publishFailure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var activeWrites = 0
    var mostConcurrentWrites = 0
    private var createdCount = 0

    override suspend fun findExisting() = existing

    override suspend fun writeTemporary(json: String): String {
        activeWrites += 1
        mostConcurrentWrites = maxOf(mostConcurrentWrites, activeWrites)
        try {
            gate?.await()
            temporaryFailure?.let { throw it }
            val uri = "content://files/new-${++createdCount}"
            files[uri] = json
            return uri
        } finally {
            activeWrites -= 1
        }
    }

    override suspend fun publish(temporaryUri: String, previousUri: String?): String {
        publishFailure?.let { throw it }
        published += temporaryUri to previousUri
        previousUri?.let { files.remove(it) }
        return temporaryUri
    }
}

private class FakeLocationStore(var saved: String? = null) : BackupLocationStore {
    override suspend fun read() = saved

    override suspend fun save(uri: String) {
        saved = uri
    }
}

class FallbackHistoryBackupFileTest {
    @Test
    fun writesACompleteFileFirstAndThenReplacesTheExistingBackup() = runTest {
        val storage = FakeBackupStorage(existing = "content://files/1").apply { files["content://files/1"] = "old" }
        val locationStore = FakeLocationStore()

        FallbackHistoryBackupFile(storage, locationStore).write("{}")

        assertEquals(listOf("content://files/new-1" to "content://files/1"), storage.published)
        assertEquals(mapOf("content://files/new-1" to "{}"), storage.files)
        assertEquals("content://files/new-1", locationStore.saved)
    }

    @Test
    fun aFailedWriteLeavesThePreviousBackupAsItWas() = runTest {
        val storage = FakeBackupStorage(existing = "content://files/1").apply {
            files["content://files/1"] = "old"
            temporaryFailure = IOException("storage full")
        }
        val locationStore = FakeLocationStore()

        failureOf<IOException> { FallbackHistoryBackupFile(storage, locationStore).write("{}") }

        assertEquals(mapOf("content://files/1" to "old"), storage.files)
        assertEquals(emptyList<Pair<String, String?>>(), storage.published)
        assertEquals(null, locationStore.saved)
    }

    @Test
    fun aFailedPublishKeepsThePreviousBackupAndTheRememberedLocation() = runTest {
        val storage = FakeBackupStorage(existing = null).apply { publishFailure = IOException("cannot rename") }
        val locationStore = FakeLocationStore(saved = "content://files/1")
        storage.files["content://files/1"] = "old"

        failureOf<IOException> { FallbackHistoryBackupFile(storage, locationStore).write("{}") }

        assertEquals("old", storage.files["content://files/1"])
        assertEquals("content://files/1", locationStore.saved)
    }

    @Test
    fun usesTheRememberedFileAsTheOneToReplace() = runTest {
        val storage = FakeBackupStorage(existing = "content://files/1")
        val locationStore = FakeLocationStore(saved = "content://files/7")

        FallbackHistoryBackupFile(storage, locationStore).write("{\"v\":2}")

        assertEquals(listOf("content://files/new-1" to "content://files/7"), storage.published)
    }

    @Test
    fun createsTheFirstFileWhenNoneExists() = runTest {
        val storage = FakeBackupStorage(existing = null)
        val locationStore = FakeLocationStore()

        FallbackHistoryBackupFile(storage, locationStore).write("{}")

        assertEquals(listOf("content://files/new-1" to null), storage.published)
        assertEquals("content://files/new-1", locationStore.saved)
    }

    @Test
    fun writesFromDifferentInstancesNeverOverlapAndTheLastWriterIsTheSavedLocation() = runTest {
        val storage = FakeBackupStorage(existing = null).apply { gate = CompletableDeferred() }
        val locationStore = FakeLocationStore()

        val writes = listOf("first", "second").map { json ->
            async { FallbackHistoryBackupFile(storage, locationStore).write(json) }
        }
        advanceUntilIdle()
        storage.gate!!.complete(Unit)
        writes.awaitAll()

        assertEquals(1, storage.mostConcurrentWrites)
        assertEquals(listOf("content://files/new-1" to null, "content://files/new-2" to "content://files/new-1"), storage.published)
        assertEquals("content://files/new-2", locationStore.saved)
    }
}
