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
    private val store = InMemorySendStore()
    private val scheduler = FakeSendScheduler()

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
            store = store,
            scheduler = scheduler,
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

    private fun matchedSong(id: Long, title: String, confirmed: Boolean = false) =
        MatchedSong(songTrack(id, title = title), "spotify:track:$title", confirmed)

    private fun unsureSong(id: Long, title: String) = UnsureSong(songTrack(id, title = title), spotifyTrack(title, durationMillis = 90_000))

    @Test
    fun sendingHandsTheChosenSourceToTheBackgroundWork() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setTopCount(25)
        viewModel.setPlaylistName("Mine")

        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf(sendRequest(SourceChoice.TopPlayed, topCount = 25, playlistName = "Mine")), scheduler.startedRequests)
    }

    @Test
    fun tickedSongsTravelWithTheRequest() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val ticked = listOf(songTrack(9, title = "Picked"))

        viewModel.useTickedSongs(ticked)
        viewModel.send()
        advanceUntilIdle()

        assertEquals(ticked, scheduler.startedRequests.single().tickedTracks)
    }

    @Test
    fun aSecondSendWhileOneRunsShowsAMessage() = runTest(dispatcher) {
        scheduler.outcome = StartOutcome.AlreadyRunning
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.send()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.message!!.contains("already running"))
    }

    @Test
    fun theScreenShowsTheProgressOfTheBackgroundWork() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        scheduler.workState.value = SendWorkState.Matching(340, 2412)
        advanceUntilIdle()
        assertEquals(SpotifyStage.Matching(340, 2412), viewModel.state.value.stage)

        scheduler.workState.value = SendWorkState.Publishing
        advanceUntilIdle()
        assertEquals(SpotifyStage.Publishing, viewModel.state.value.stage)
    }

    @Test
    fun cancellingAsksTheSchedulerToStop() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.cancelSend()

        assertEquals(1, scheduler.cancelCount)
    }

    @Test
    fun aSavedResultIsShownWhenTheScreenIsCreatedAgain() = runTest(dispatcher) {
        store.save(savedSend(added = listOf(matchedSong(1, "One")), unsure = listOf(unsureSong(2, "Maybe")), notFound = listOf(songTrack(3))))

        val viewModel = viewModel()
        advanceUntilIdle()

        val result = done(viewModel)
        assertEquals(listOf(1L), result.result.added.map { it.track.id })
        assertEquals(listOf(2L), result.unsure.map { it.track.id })
        assertEquals(listOf(3L), result.result.notFound.map { it.id })
    }

    @Test
    fun aSavedFailureIsShownAsFailed() = runTest(dispatcher) {
        store.save(failedSend(sendRequest(), "Spotify answered with an error (500). Try again in a minute."))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue((viewModel.state.value.stage as SpotifyStage.Failed).message.contains("500"))
    }

    @Test
    fun theOfferHoldsOnlyMatchedSongsStartsUntickedAndSelectAllTicksEverything() = runTest(dispatcher) {
        store.save(
            savedSend(
                added = listOf(matchedSong(1, "One"), matchedSong(2, "Two"), matchedSong(4, "Confirmed", confirmed = true)),
                unsure = listOf(unsureSong(5, "Maybe")),
                notFound = listOf(songTrack(3)),
            ),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), done(viewModel).offer.map { it.track.id })
        assertEquals(listOf(5_000_000L, null), done(viewModel).offer.map { it.sizeBytes })
        assertTrue(done(viewModel).selectedIds.isEmpty())
        viewModel.selectAllOffered()
        assertEquals(setOf(1L, 2L), done(viewModel).selectedIds)
        viewModel.toggleOffered(1)
        assertEquals(setOf(2L), done(viewModel).selectedIds)
        viewModel.clearOffered()
        assertTrue(done(viewModel).selectedIds.isEmpty())
    }

    @Test
    fun confirmingTheLastUnsureSongUpdatesTheSamePlaylistButIsNotOfferedForTheTrash() = runTest(dispatcher) {
        store.save(savedSend(added = listOf(matchedSong(1, "Sure")), unsure = listOf(unsureSong(2, "Maybe"))))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.confirmUnsure()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), done(viewModel).result.added.map { it.track.id })
        assertEquals(listOf(1L), done(viewModel).offer.map { it.track.id })
        assertTrue(done(viewModel).unsure.isEmpty())
        assertEquals(listOf("spotify:track:Sure", "spotify:track:Maybe"), api.replaceCalls.single().second)
    }

    @Test
    fun skippingTheLastUnsureSongLeavesThePlaylistAlone() = runTest(dispatcher) {
        store.save(savedSend(added = listOf(matchedSong(1, "Sure")), unsure = listOf(unsureSong(2, "Maybe"))))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.skipUnsure()
        advanceUntilIdle()

        assertEquals(listOf(2L), done(viewModel).result.skippedUnsure.map { it.track.id })
        assertEquals(listOf(1L), done(viewModel).result.added.map { it.track.id })
        assertTrue(api.replaceCalls.isEmpty())
    }

    @Test
    fun confirmingAnUnsureSongWhenNothingElseMatchedMakesThePlaylist() = runTest(dispatcher) {
        store.save(savedSend(unsure = listOf(unsureSong(2, "Maybe")), playlist = null))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.confirmUnsure()
        advanceUntilIdle()

        assertEquals(listOf("Play Sift picks"), api.createdNames)
        assertEquals("playlist-1", done(viewModel).result.playlist!!.id)
    }

    @Test
    fun skippingEveryUnsureSongWithNothingAddedEndsAsFailed() = runTest(dispatcher) {
        store.save(savedSend(unsure = listOf(unsureSong(2, "Maybe")), playlist = null))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.skipUnsure()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.stage is SpotifyStage.Failed)
    }

    @Test
    fun backToSetupForgetsTheSavedResult() = runTest(dispatcher) {
        store.save(savedSend(added = listOf(matchedSong(1, "One"))))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.backToSetup()
        advanceUntilIdle()

        assertEquals(SpotifyStage.Setup, viewModel.state.value.stage)
    }

    @Test
    fun unsureSongsFromABackgroundRunAppearOnTheResultScreen() = runTest(dispatcher) {
        api.catalogue = mapOf("Sure" to listOf(spotifyTrack("Sure")), "Maybe" to listOf(spotifyTrack("Maybe", durationMillis = 90_000)))
        val runner = SendRunner(SongMatcher(api, dao, clock), PlaylistPublisher(api, dao)) { emptyList() }
        val request = sendRequest(tickedTracks = listOf(songTrack(1, title = "Sure"), songTrack(2, title = "Maybe")))

        store.save(runner.run(request, { _, _ -> }, {}))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("Maybe"), done(viewModel).unsure.map { it.track.title })
        assertEquals(listOf("Sure"), done(viewModel).result.added.map { it.track.title })
    }
}
