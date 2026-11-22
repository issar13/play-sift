package app.playsift.spotify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSendStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun dataStore() = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "store.preferences_pb") }

    private val lastPlayedTrack = songTrack(7, title = "Played").copy(lastPlayedAt = Instant.parse("2026-11-20T10:00:00Z"))

    private val saved = savedSend(
        added = listOf(MatchedSong(songTrack(1), "spotify:track:1", wasConfirmedByUser = false)),
        unsure = listOf(UnsureSong(lastPlayedTrack, spotifyTrack("Played"))),
        notFound = listOf(songTrack(3)),
    )

    @Test
    fun theResultSurvivesANewStoreOnTheSameFile() = runTest {
        DataStoreSendStore(dataStore()).save(saved)
        scope.cancel()

        val reopened = DataStoreSendStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { File(folder.root, "store.preferences_pb") })

        assertEquals(saved, reopened.saved.first())
    }

    @Test
    fun theRequestRoundTripsWithItsTickedSongs() = runTest {
        val store = DataStoreSendStore(dataStore())
        val request = sendRequest(tickedTracks = listOf(lastPlayedTrack))

        store.saveRequest(request)

        assertEquals(request, store.request())
    }

    @Test
    fun clearingForgetsTheResultButNotTheRequest() = runTest {
        val store = DataStoreSendStore(dataStore())
        store.save(saved)
        store.saveRequest(sendRequest())

        store.clearSaved()

        assertNull(store.saved.first())
        assertEquals(sendRequest(), store.request())
    }

    @Test
    fun aSavedValueThatNoLongerReadsCountsAsNothingSaved() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[stringPreferencesKey("spotify_saved_send")] = "{not json" }

        assertNull(DataStoreSendStore(dataStore).saved.first())
    }
}
