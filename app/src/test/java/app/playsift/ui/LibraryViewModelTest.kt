package app.playsift.ui

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.playsift.files.FileMatcher
import app.playsift.files.NotRemovableReason
import app.playsift.files.TrackFile
import app.playsift.player.Access
import app.playsift.player.AccessState
import app.playsift.player.HistoryHealth
import app.playsift.player.HistorySummary
import app.playsift.player.Library
import app.playsift.player.LibraryList
import app.playsift.player.PlayerTrack
import app.playsift.settings.CutoffOption
import app.playsift.settings.CutoffSettings
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val now = Instant.parse("2026-06-01T00:00:00Z")

private fun track(id: Long) = PlayerTrack(
    id = id, title = "t$id", artist = "a", album = "b", fileName = "$id.mp3", folderPath = "primary/Music/",
    filePath = null, isCueTrack = false, isUserAdded = false, playCount = 0, lastPlayedAt = null,
    firstSeenAt = Instant.parse("2020-01-01T00:00:00Z"), durationMillis = 1000,
)

private class FakeLibrary(var tracks: List<PlayerTrack>, var summary: HistorySummary) : Library {
    var trackQueries = 0
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun tracks(list: LibraryList, cutoff: Period, now: Instant): List<PlayerTrack> {
        trackQueries += 1
        gate?.await()
        failure?.let { throw it }
        return tracks
    }

    override suspend fun historySummary(): HistorySummary = summary
}

private class FakeAccess(var accessState: AccessState) : Access {
    var failure: Throwable? = null
    override fun state(): AccessState {
        failure?.let { throw it }
        return accessState
    }
    override fun requestPermission() = Unit
    var rescanRequests = 0
    override fun requestRescan() {
        rescanRequests += 1
    }
    override fun openPlayer() = Unit
}

/** Every track becomes removable unless its id is in [notRemovableIds]. */
private class FakeMatcher(val notRemovableIds: Set<Long> = emptySet(), val sizeBytes: Long = 100) : FileMatcher {
    var matchedTracks: List<PlayerTrack> = emptyList()
    var gate: CompletableDeferred<Unit>? = null
    var matchCalls = 0
    override suspend fun match(tracks: List<PlayerTrack>): List<TrackFile> {
        matchCalls += 1
        gate?.await()
        matchedTracks = tracks
        return tracks.map {
            if (it.id in notRemovableIds) TrackFile.NotRemovable(it, NotRemovableReason.NotFoundOnPhone)
            else TrackFile.Removable(it, Uri.parse("content://media/${it.id}"), sizeBytes)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val healthySummary = HistorySummary(Instant.parse("2020-01-01T00:00:00Z"), totalTracks = 100, unplayedTracks = 10)
    private lateinit var settingsScope: CoroutineScope
    private lateinit var settings: CutoffSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settingsScope = CoroutineScope(SupervisorJob() + dispatcher)
        settings = CutoffSettings(
            PreferenceDataStoreFactory.create(scope = settingsScope) { File(temporaryFolder.root, "settings.preferences_pb") },
        )
    }

    @After
    fun tearDown() {
        settingsScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        library: FakeLibrary = FakeLibrary((1L..3L).map(::track), healthySummary),
        access: FakeAccess = FakeAccess(AccessState.Ready),
        matcher: FakeMatcher = FakeMatcher(),
    ) = LibraryViewModel(library, access, matcher, settings, clock)

    @Test
    fun startsOnAccessScreenWhenPermissionMissing() = runTest(dispatcher) {
        val library = FakeLibrary((1L..3L).map(::track), healthySummary)
        val viewModel = viewModel(library = library, access = FakeAccess(AccessState.NeedsPermission))
        advanceUntilIdle()
        assertEquals(AccessState.NeedsPermission, viewModel.state.value.access)
        assertTrue(viewModel.state.value.tracks.isEmpty())
        assertEquals(0, library.trackQueries)
    }

    @Test
    fun selectionClearedOnRefresh() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelected(1)
        assertEquals(setOf(1L), viewModel.state.value.selectedIds)
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.selectedIds.isEmpty())
    }

    @Test
    fun selectionClearedOnCutoffChange() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectAll()
        viewModel.setCutoff(CutoffOption.TwoYears)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.selectedIds.isEmpty())
        assertEquals(CutoffOption.TwoYears, viewModel.state.value.cutoff)
    }

    @Test
    fun selectionClearedOnListChange() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelected(2)
        viewModel.showList(LibraryList.NeverPlayed)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.selectedIds.isEmpty())
        assertEquals(LibraryList.NeverPlayed, viewModel.state.value.list)
    }

    @Test
    fun selectAllSelectsEveryCandidate() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectAll()
        assertEquals(setOf(1L, 2L, 3L), viewModel.state.value.selectedIds)
    }

    @Test
    fun prepareTrashSumsSizesAndSplitsNotRemovable() = runTest(dispatcher) {
        val matcher = FakeMatcher(notRemovableIds = setOf(3L))
        val viewModel = viewModel(matcher = matcher)
        advanceUntilIdle()
        viewModel.toggleSelected(1)
        viewModel.toggleSelected(3)
        val plan = viewModel.prepareTrash()!!
        assertEquals(listOf(1L, 3L), matcher.matchedTracks.map { it.id })
        assertEquals(listOf(1L), plan.removable.map { it.track.id })
        assertEquals(listOf(3L), plan.notRemovable.map { it.track.id })
        assertEquals(100L, plan.totalSizeBytes)
        assertEquals(1, plan.batches.size)
    }

    private suspend fun TestScope.confirmedFlow(viewModel: LibraryViewModel, trackCount: Long): TrashPlan {
        advanceUntilIdle()
        viewModel.selectAll()
        return viewModel.prepareTrash()!!.also { assertEquals(trackCount.toInt(), it.removable.size) }
    }

    @Test
    fun batchesAdvanceInOrderAndFinishWithSummary() = runTest(dispatcher) {
        val viewModel = viewModel(library = FakeLibrary((1L..1000L).map(::track), healthySummary))
        confirmedFlow(viewModel, 1000)
        viewModel.confirmTrash()
        val first = viewModel.trashFlow.value as TrashFlow.InProgress
        assertEquals(500, first.currentBatch.size)
        assertEquals(1L, first.currentBatch.first().track.id)
        viewModel.onBatchResult(resultOk = true)
        val second = viewModel.trashFlow.value as TrashFlow.InProgress
        assertEquals(501L, second.currentBatch.first().track.id)
        viewModel.onBatchResult(resultOk = false)
        assertEquals(TrashFlow.Done(movedCount = 500, attemptedCount = 1000), viewModel.trashFlow.value)
    }

    @Test
    fun confirmTwiceStartsOnlyOneFlow() = runTest(dispatcher) {
        val viewModel = viewModel(library = FakeLibrary((1L..1000L).map(::track), healthySummary))
        confirmedFlow(viewModel, 1000)
        viewModel.confirmTrash()
        viewModel.confirmTrash()
        val inProgress = viewModel.trashFlow.value as TrashFlow.InProgress
        assertEquals(1, inProgress.remainingBatches.size)
        assertEquals(viewModel.takeBatchToLaunch()?.size, 500)
    }

    @Test
    fun rescanRequestedOnceWhenFlowCompletes() = runTest(dispatcher) {
        val access = FakeAccess(AccessState.Ready)
        val viewModel = viewModel(access = access)
        confirmedFlow(viewModel, 3)
        viewModel.confirmTrash()
        assertEquals(0, access.rescanRequests)
        viewModel.onBatchResult(resultOk = true)
        viewModel.trashFlow.value
        viewModel.trashFlow.value
        assertEquals(1, access.rescanRequests)
        assertEquals(TrashFlow.Done(3, 3), viewModel.trashFlow.value)
    }

    @Test
    fun cancelReturnsToIdleWithoutTrashing() = runTest(dispatcher) {
        val viewModel = viewModel()
        confirmedFlow(viewModel, 3)
        assertTrue(viewModel.trashFlow.value is TrashFlow.Confirming)
        viewModel.cancelTrash()
        assertEquals(TrashFlow.Idle, viewModel.trashFlow.value)
        assertEquals(null, viewModel.takeBatchToLaunch())
    }

    @Test
    fun launchedBatchIsNotRelaunchedAfterRecreation() = runTest(dispatcher) {
        val viewModel = viewModel()
        confirmedFlow(viewModel, 3)
        viewModel.confirmTrash()
        assertEquals(3, viewModel.takeBatchToLaunch()?.size)
        assertEquals(null, viewModel.takeBatchToLaunch())
    }

    @Test
    fun finishReturnsToIdleAndRefreshes() = runTest(dispatcher) {
        val library = FakeLibrary((1L..3L).map(::track), healthySummary)
        val viewModel = viewModel(library = library)
        confirmedFlow(viewModel, 3)
        viewModel.confirmTrash()
        viewModel.onBatchResult(resultOk = true)
        val queriesBefore = library.trackQueries
        viewModel.finishTrash()
        advanceUntilIdle()
        assertEquals(TrashFlow.Idle, viewModel.trashFlow.value)
        assertEquals(queriesBefore + 1, library.trackQueries)
    }

    @Test
    fun showsMostlyUnplayedBanner() = runTest(dispatcher) {
        val library = FakeLibrary((1L..3L).map(::track), HistorySummary(Instant.parse("2020-01-01T00:00:00Z"), 100, 98))
        val viewModel = viewModel(library = library)
        advanceUntilIdle()
        assertEquals(HistoryHealth.MostlyUnplayed, viewModel.state.value.historyHealth)
    }

    @Test
    fun securityExceptionDuringLoadShowsAccessScreen() = runTest(dispatcher) {
        val library = FakeLibrary((1L..3L).map(::track), healthySummary)
        val viewModel = viewModel(library = library)
        advanceUntilIdle()
        library.failure = SecurityException("revoked")
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(AccessState.NeedsPermission, viewModel.state.value.access)
        assertTrue(viewModel.state.value.tracks.isEmpty())
        assertEquals(false, viewModel.state.value.isLoading)
    }

    @Test
    fun otherLoadFailureSetsLoadFailed() = runTest(dispatcher) {
        val library = FakeLibrary((1L..3L).map(::track), healthySummary)
        val viewModel = viewModel(library = library)
        advanceUntilIdle()
        library.failure = IllegalStateException("null cursor")
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.loadFailed)
        assertTrue(viewModel.state.value.tracks.isEmpty())
        assertEquals(false, viewModel.state.value.isLoading)
    }

    @Test
    fun selectionMadeDuringLoadIsClearedWhenResultsArrive() = runTest(dispatcher) {
        val library = FakeLibrary((1L..3L).map(::track), healthySummary)
        val viewModel = viewModel(library = library)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        library.gate = gate
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.toggleSelected(1)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.selectedIds.isEmpty())
    }

    @Test
    fun cutoffPersistsEvenIfReloadIsCancelled() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.setCutoff(CutoffOption.TwoYears)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(CutoffOption.TwoYears, settings.cutoff.first())
    }

    @Test
    fun savedCutoffUsedEvenIfRefreshCalledImmediately() = runTest(dispatcher) {
        settings.setCutoff(CutoffOption.TwoYears)
        val viewModel = viewModel()
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(CutoffOption.TwoYears, viewModel.state.value.cutoff)
        assertEquals(3, viewModel.state.value.tracks.size)
    }

    @Test
    fun resumeWhileReadyKeepsSelection() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelected(2)
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(setOf(2L), viewModel.state.value.selectedIds)
    }

    @Test
    fun resumeWhileNotReadyRefreshes() = runTest(dispatcher) {
        val access = FakeAccess(AccessState.NeedsPermission)
        val viewModel = viewModel(access = access)
        advanceUntilIdle()
        access.accessState = AccessState.Ready
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(AccessState.Ready, viewModel.state.value.access)
        assertEquals(3, viewModel.state.value.tracks.size)
    }

    @Test
    fun selectingCurrentTabKeepsSelection() = runTest(dispatcher) {
        val library = FakeLibrary((1L..3L).map(::track), healthySummary)
        val viewModel = viewModel(library = library)
        advanceUntilIdle()
        viewModel.toggleSelected(1)
        val queriesBefore = library.trackQueries
        viewModel.showList(LibraryList.DeleteCandidates)
        advanceUntilIdle()
        assertEquals(setOf(1L), viewModel.state.value.selectedIds)
        assertEquals(queriesBefore, library.trackQueries)
    }

    @Test
    fun accessProbeFailureShowsLoadFailed() = runTest(dispatcher) {
        val access = FakeAccess(AccessState.Ready)
        access.failure = IllegalStateException("probe blew up")
        val viewModel = viewModel(access = access)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.loadFailed)
        assertEquals(false, viewModel.state.value.isLoading)
    }

    @Test
    fun repeatPrepareTrashWhilePreparingIsIgnored() = runTest(dispatcher) {
        val matcher = FakeMatcher()
        val viewModel = viewModel(matcher = matcher)
        advanceUntilIdle()
        viewModel.selectAll()
        val gate = CompletableDeferred<Unit>()
        matcher.gate = gate
        val first = async { viewModel.prepareTrash() }
        advanceUntilIdle()
        assertTrue(viewModel.isPreparingTrash.value)
        assertEquals(null, viewModel.prepareTrash())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, first.await()!!.removable.size)
        assertEquals(1, matcher.matchCalls)
        assertEquals(false, viewModel.isPreparingTrash.value)
    }

    @Test
    fun prepareTrashUsesGivenSelectionSnapshot() = runTest(dispatcher) {
        val matcher = FakeMatcher()
        val viewModel = viewModel(matcher = matcher)
        advanceUntilIdle()
        viewModel.toggleSelected(1)
        viewModel.clearSelection()
        viewModel.prepareTrash(selectedIds = setOf(1L, 2L))
        assertEquals(listOf(1L, 2L), matcher.matchedTracks.map { it.id })
    }
}
