package app.playsift.spotify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs the real worker through WorkManager's test setup with a fake Spotify API. No network. */
@RunWith(RobolectricTestRunner::class)
class SendWorkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC)
    private val api = FakeSpotifyApi()
    private val dao = FakeSpotifyDao()
    private val store = InMemorySendStore()
    private lateinit var scheduler: WorkManagerSendScheduler

    private val tickedSongs = listOf(songTrack(1, title = "One"), songTrack(2, title = "Two"), songTrack(3, title = "Three"))
    private val request = sendRequest(tickedTracks = tickedSongs)

    @Before
    fun setUp() {
        val services = SendServices(SendRunner(SongMatcher(api, dao, clock), PlaylistPublisher(api, dao), FakePlayerPlaylists()) { emptyList() }, store, SendNotifications(context))
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                SpotifySendWorker(appContext, workerParameters, services).takeIf { workerClassName == SpotifySendWorker::class.java.name }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
        scheduler = WorkManagerSendScheduler(WorkManager.getInstance(context), store)
        api.catalogue = tickedSongs.associate { it.title to listOf(spotifyTrack(it.title)) }
    }

    private fun <T> waiting(block: suspend () -> T): T = runBlocking { withTimeout(WAIT_MILLIS) { block() } }

    private suspend fun waitForState(expected: SendWorkState) = scheduler.state.first { it == expected }

    @Test
    fun theWorkerRunsASendAndReportsProgress() = waiting {
        val secondSearchReached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeSearch = { title ->
            if (title == "Two") {
                secondSearchReached.complete(Unit)
                release.await()
            }
        }

        assertEquals(StartOutcome.Started, scheduler.start(request))
        secondSearchReached.await()
        waitForState(SendWorkState.Matching(1, 3))
        release.complete(Unit)
        waitForState(SendWorkState.Idle)

        val saved = store.saved.first()!!
        assertEquals(listOf(1L, 2L, 3L), saved.result.added.map { it.track.id })
        assertEquals("playlist-1", saved.result.playlist!!.id)
        assertNull(saved.failureMessage)
    }

    @Test
    fun cancellingKeepsTheCachedMatchesAndSavesNoResult() = waiting {
        val thirdSearchReached = CompletableDeferred<Unit>()
        api.beforeSearch = { title ->
            if (title == "Three") {
                thirdSearchReached.complete(Unit)
                awaitCancellation()
            }
        }

        scheduler.start(request)
        thirdSearchReached.await()
        scheduler.cancel()
        waitForState(SendWorkState.Idle)

        assertEquals(setOf("1.mp3", "2.mp3"), dao.matches.keys.map { it.second }.toSet())
        assertNull(store.saved.first())
        assertTrue(api.createdNames.isEmpty())
    }

    @Test
    fun aSecondSendIsRefusedWhileOneRuns() = waiting {
        val searchReached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeSearch = {
            searchReached.complete(Unit)
            release.await()
        }

        assertEquals(StartOutcome.Started, scheduler.start(request))
        searchReached.await()
        assertEquals(StartOutcome.AlreadyRunning, scheduler.start(request))
        release.complete(Unit)
        waitForState(SendWorkState.Idle)

        assertEquals(1, api.createdNames.size)
        assertEquals(StartOutcome.Started, scheduler.start(request))
    }

    @Test
    fun twoStartsAtTheSameTimeAdmitOneRequestAndKeepItsSavedRequest() = waiting {
        val gate = CompletableDeferred<Unit>()
        val slowStore = object : SendStore by store {
            override suspend fun clearSaved() {
                gate.await()
                store.clearSaved()
            }
        }
        scheduler = WorkManagerSendScheduler(WorkManager.getInstance(context), slowStore)
        val release = CompletableDeferred<Unit>()
        api.beforeSearch = { release.await() }
        val firstRequest = sendRequest(playlistName = "First", tickedTracks = tickedSongs)
        val secondRequest = sendRequest(playlistName = "Second", tickedTracks = tickedSongs)

        val outcomes = coroutineScope {
            val first = async { scheduler.start(firstRequest) }
            val second = async { scheduler.start(secondRequest) }
            yield()
            gate.complete(Unit)
            listOf(first.await(), second.await())
        }
        release.complete(Unit)
        waitForState(SendWorkState.Idle)

        assertEquals(listOf(StartOutcome.Started, StartOutcome.AlreadyRunning), outcomes)
        assertEquals(firstRequest, store.storedRequest)
    }

    @Test
    fun aFailedSendIsSavedWithItsReason() = waiting {
        api.failure = SpotifyHttpException(403, "forbidden")

        scheduler.start(request)
        waitForState(SendWorkState.Idle)

        assertTrue(store.saved.first()!!.failureMessage!!.contains("developer dashboard"))
    }

    @Test
    fun aRateLimitWaitDuringASendDoesNotStopIt() = waiting {
        api.beforeSearch = { title -> if (title == "Two") kotlin.coroutines.coroutineContext[RateLimitWaitHook]!!.onWait(90) }

        scheduler.start(request)
        waitForState(SendWorkState.Idle)

        assertEquals(listOf(1L, 2L, 3L), store.saved.first()!!.result.added.map { it.track.id })
    }

    private companion object {
        const val WAIT_MILLIS = 20_000L
    }
}
