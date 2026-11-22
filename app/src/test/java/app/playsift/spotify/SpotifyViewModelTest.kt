package app.playsift.spotify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.playsift.files.FileLocation
import app.playsift.files.FileSizes
import app.playsift.player.PlayerTrack
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC)
    private val dataStoreScope = CoroutineScope(dispatcher + SupervisorJob())
    private val api = FakeSpotifyApi()
    private val dao = FakeSpotifyDao()
    private val accounts = FakeAccounts()
    private val tokenStore = InMemoryTokenStore(SpotifyTokens("access", "refresh", Instant.parse("2026-11-22T01:00:00Z")))
    private var mergedTrackLoads = 0
    private var mergedTracks = listOf<PlayerTrack>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(builtInClientId: String = "built-in"): SpotifyViewModel {
        val dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { File(folder.root, "settings.preferences_pb") }
        val clientId = SpotifyClientId(dataStore, builtInClientId)
        val fileSizes = object : FileSizes {
            override suspend fun sizesByLocation() = mapOf(FileLocation("Music/", "1.mp3") to 5_000_000L)
        }
        return SpotifyViewModel(
            session = SpotifySession(tokenStore, accounts, clientId::effective, clock),
            tokenStore = tokenStore,
            clientId = clientId,
            api = api,
            matcher = SongMatcher(api, dao, clock),
            publisher = PlaylistPublisher(api, dao),
            fileSizes = fileSizes,
            loadMergedTracks = { mergedTrackLoads += 1; mergedTracks },
            builtInClientId = builtInClientId,
        )
    }

    private fun done(viewModel: SpotifyViewModel) = viewModel.state.value.stage as SpotifyStage.Done

    @Test
    fun signedInStateIsReadFromTheStoredTokens() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isSignedIn)
        assertEquals("Test User", viewModel.state.value.userName)
    }

    @Test
    fun missingClientIdBlocksSignIn() = runTest(dispatcher) {
        tokenStore.stored = null
        val viewModel = viewModel(builtInClientId = "")
        advanceUntilIdle()
        var openedUrl: String? = null

        viewModel.signIn { openedUrl = it }
        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.hasClientId)
        assertEquals(null, openedUrl)
    }

    @Test
    fun aTypedClientIdOverridesTheBuiltInOne() = runTest(dispatcher) {
        tokenStore.stored = null
        val viewModel = viewModel(builtInClientId = "")
        advanceUntilIdle()
        viewModel.setClientIdOverride("typed-id")
        advanceUntilIdle()
        var openedUrl: String? = null

        viewModel.signIn { openedUrl = it }
        advanceUntilIdle()

        assertTrue(openedUrl!!.contains("client_id=typed-id"))
    }

    @Test
    fun signInSavesAVerifierAndOpensThePageWithItsChallenge() = runTest(dispatcher) {
        tokenStore.stored = null
        val viewModel = viewModel()
        advanceUntilIdle()
        var openedUrl: String? = null

        viewModel.signIn { openedUrl = it }
        advanceUntilIdle()

        val pending = tokenStore.pending!!
        assertTrue(openedUrl!!.contains("code_challenge=${codeChallengeFor(pending.verifier)}"))
        assertTrue(openedUrl!!.contains("state=${pending.state}"))
    }

    @Test
    fun aCallbackWithTheWrongStateIsRefused() = runTest(dispatcher) {
        tokenStore.stored = null
        tokenStore.pending = PendingAuthorization("verifier", "expected-state")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAuthCallback(code = "code", state = "other-state", error = null)
        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.isSignedIn)
        assertEquals(null, accounts.exchangedWith)
        assertNotNull(viewModel.state.value.message)
    }

    @Test
    fun aCallbackWithMatchingStateCompletesSignIn() = runTest(dispatcher) {
        tokenStore.stored = null
        tokenStore.pending = PendingAuthorization("verifier", "expected-state")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAuthCallback(code = "code", state = "expected-state", error = null)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isSignedIn)
        assertEquals(Triple("built-in", "code", "verifier"), accounts.exchangedWith)
    }

    @Test
    fun aDeniedSignInShowsAMessage() = runTest(dispatcher) {
        tokenStore.stored = null
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onAuthCallback(code = null, state = "x", error = "access_denied")
        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.isSignedIn)
        assertNotNull(viewModel.state.value.message)
    }

    @Test
    fun signOutForgetsTheTokens() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.isSignedIn)
        assertEquals(null, tokenStore.stored)
    }

    @Test
    fun sendingTopPlayedMakesANamedPlaylistAndOffersOnlyMatchedSongs() = runTest(dispatcher) {
        mergedTracks = listOf(
            songTrack(1, title = "One", playCount = 30),
            songTrack(2, title = "Two", playCount = 20),
            songTrack(3, title = "Three", playCount = 10),
            songTrack(4, title = "Never", playCount = 0),
        )
        api.catalogue = mapOf("One" to listOf(spotifyTrack("One")), "Two" to listOf(spotifyTrack("Two")))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setTopCount(3)

        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("Play Sift top 3"), api.createdNames)
        assertEquals(listOf("One", "Two", "Three"), api.searchedTitles)
        val result = done(viewModel).result
        assertEquals(listOf(1L, 2L), result.added.map { it.track.id })
        assertEquals(listOf(3L), result.notFound.map { it.id })
        assertEquals(listOf(1L, 2L), done(viewModel).offer.map { it.track.id })
        assertEquals(listOf(5_000_000L, null), done(viewModel).offer.map { it.sizeBytes })
        assertTrue(done(viewModel).selectedIds.isEmpty())
    }

    @Test
    fun theOfferStartsUntickedAndSelectAllTicksEverything() = runTest(dispatcher) {
        mergedTracks = listOf(songTrack(1, title = "One", playCount = 3), songTrack(2, title = "Two", playCount = 2))
        api.catalogue = mapOf("One" to listOf(spotifyTrack("One")), "Two" to listOf(spotifyTrack("Two")))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.chooseSource(SourceChoice.EverythingPlayed)
        viewModel.send()
        advanceUntilIdle()

        assertTrue(done(viewModel).selectedIds.isEmpty())
        viewModel.selectAllOffered()
        assertEquals(setOf(1L, 2L), done(viewModel).selectedIds)
        viewModel.toggleOffered(1)
        assertEquals(setOf(2L), done(viewModel).selectedIds)
        viewModel.clearOffered()
        assertTrue(done(viewModel).selectedIds.isEmpty())
    }

    @Test
    fun anUnsureSongIsAskedAboutAndAddedWhenConfirmedButNotOfferedForTheTrash() = runTest(dispatcher) {
        mergedTracks = listOf(songTrack(1, title = "Sure", playCount = 3), songTrack(2, title = "Maybe", playCount = 2))
        api.catalogue = mapOf("Sure" to listOf(spotifyTrack("Sure")), "Maybe" to listOf(spotifyTrack("Maybe", durationMillis = 90_000)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.send()
        advanceUntilIdle()
        val asking = viewModel.state.value.stage as SpotifyStage.ConfirmingUnsure
        assertEquals("Maybe", asking.pending.first().track.title)
        viewModel.confirmUnsure()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), done(viewModel).result.added.map { it.track.id })
        assertEquals(listOf(1L), done(viewModel).offer.map { it.track.id })
        assertEquals(listOf("spotify:track:Sure", "spotify:track:Maybe"), api.replaceCalls.single().second)
    }

    @Test
    fun aSkippedUnsureSongIsNotAddedAndNotOffered() = runTest(dispatcher) {
        mergedTracks = listOf(songTrack(1, title = "Sure", playCount = 3), songTrack(2, title = "Maybe", playCount = 2))
        api.catalogue = mapOf("Sure" to listOf(spotifyTrack("Sure")), "Maybe" to listOf(spotifyTrack("Maybe", durationMillis = 90_000)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.send()
        advanceUntilIdle()
        viewModel.skipUnsure()
        advanceUntilIdle()

        assertEquals(listOf(1L), done(viewModel).result.added.map { it.track.id })
        assertEquals(listOf(2L), done(viewModel).result.skippedUnsure.map { it.track.id })
        assertEquals(listOf("spotify:track:Sure"), api.replaceCalls.single().second)
    }

    @Test
    fun sendingTheSameSourceTwiceUpdatesTheSamePlaylist() = runTest(dispatcher) {
        mergedTracks = listOf(songTrack(1, title = "One", playCount = 3))
        api.catalogue = mapOf("One" to listOf(spotifyTrack("One")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.send()
        advanceUntilIdle()
        viewModel.backToSetup()
        viewModel.send()
        advanceUntilIdle()

        assertEquals(1, api.createdNames.size)
        assertEquals(2, api.replaceCalls.size)
        assertEquals(listOf("One"), api.searchedTitles)
    }

    @Test
    fun tickedSongsAreSentWithoutReadingTheWholeLibrary() = runTest(dispatcher) {
        api.catalogue = mapOf("Picked" to listOf(spotifyTrack("Picked")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.useTickedSongs(listOf(songTrack(9, title = "Picked", playCount = 0)))
        viewModel.send()
        advanceUntilIdle()

        assertEquals(0, mergedTrackLoads)
        assertEquals(listOf("Play Sift picks"), api.createdNames)
        assertEquals(listOf(9L), done(viewModel).result.added.map { it.track.id })
    }

    @Test
    fun aBlankPlaylistNameFallsBackToTheDefault() = runTest(dispatcher) {
        api.catalogue = mapOf("Picked" to listOf(spotifyTrack("Picked")))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.useTickedSongs(listOf(songTrack(9, title = "Picked")))
        viewModel.setPlaylistName("   ")

        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("Play Sift picks"), api.createdNames)
    }

    @Test
    fun whenNothingMatchesNoPlaylistIsMade() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.useTickedSongs(listOf(songTrack(9, title = "Nowhere")))

        viewModel.send()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.stage is SpotifyStage.Failed)
        assertTrue(api.createdNames.isEmpty())
    }

    @Test
    fun aForbiddenAnswerExplainsTheDashboardRequirement() = runTest(dispatcher) {
        api.failure = SpotifyHttpException(403, "forbidden")
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.useTickedSongs(listOf(songTrack(9)))

        viewModel.send()
        advanceUntilIdle()

        val failed = viewModel.state.value.stage as SpotifyStage.Failed
        assertTrue(failed.message.contains("developer dashboard"))
    }
}
