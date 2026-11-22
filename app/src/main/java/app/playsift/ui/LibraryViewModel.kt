package app.playsift.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.playsift.files.FileLocation
import app.playsift.files.FileMatcher
import app.playsift.files.FileSizes
import app.playsift.files.MediaStoreFileSizes
import app.playsift.files.TrackFile
import app.playsift.files.TrackFileMatcher
import app.playsift.files.trashBatches
import app.playsift.history.PlayHistory
import app.playsift.history.PlayHistoryDatabase
import app.playsift.history.RoomPlayHistory
import app.playsift.history.historySummary
import app.playsift.history.mergeHistory
import app.playsift.history.tracksFor
import app.playsift.player.Access
import app.playsift.player.AccessState
import app.playsift.player.HistoryHealth
import app.playsift.player.HistorySummary
import app.playsift.player.Library
import app.playsift.player.LibraryList
import app.playsift.player.PlayerAccess
import app.playsift.player.PlayerLibrary
import app.playsift.player.PlayerTrack
import app.playsift.player.historyHealth
import app.playsift.settings.CutoffOption
import app.playsift.settings.CutoffSettings
import app.playsift.settings.settingsDataStore
import java.time.Clock
import java.time.Instant
import java.time.Period
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val library: Library,
    private val history: PlayHistory,
    private val access: Access,
    private val matcher: FileMatcher,
    private val fileSizes: FileSizes,
    private val settings: CutoffSettings,
    private val clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        LibraryUiState(
            access = AccessState.NeedsPermission,
            list = LibraryList.AllTracks,
            screen = Screen.Home,
            tracks = emptyList(),
            historyHealth = HistoryHealth.Healthy,
            cutoff = CutoffOption.OneYear,
            selectedIds = emptySet(),
            isLoading = true,
        ),
    )
    val state: StateFlow<LibraryUiState> = mutableState

    private var loadJob: Job? = null

    /** Own job, so a reload can never cancel it; every load waits for it before reading the cutoff. */
    private val savedCutoffJob: Job = viewModelScope.launch {
        val savedCutoff = settings.cutoff.first()
        mutableState.update { it.copy(cutoff = savedCutoff) }
    }
    private val mutableIsPreparingTrash = MutableStateFlow(false)
    val isPreparingTrash: StateFlow<Boolean> = mutableIsPreparingTrash
    private val mutableTrashFlow = MutableStateFlow<TrashFlow>(TrashFlow.Idle)
    val trashFlow: StateFlow<TrashFlow> = mutableTrashFlow

    private val mutableMessage = MutableStateFlow<UserMessage?>(null)
    val message: StateFlow<UserMessage?> = mutableMessage

    private var isCurrentBatchAwaitingLaunch = false

    init {
        loadJob = viewModelScope.launch { load() }
    }

    fun showScreen(screen: Screen) {
        if (screen == mutableState.value.screen) return
        mutableState.update { it.copy(screen = screen, list = screen.libraryList() ?: it.list, selectedIds = emptySet()) }
        reload()
    }

    /** The UI reports the media permission here; sizes are only read once it is granted. */
    fun setFileAccess(granted: Boolean) {
        if (granted == mutableState.value.hasFileAccess) return
        mutableState.update { it.copy(hasFileAccess = granted) }
        reload()
    }

    fun refresh() = reload()

    /** Only refreshes while access is missing, e.g. when returning from the player's permission prompt. */
    fun onResume() {
        if (mutableState.value.access != AccessState.Ready) refresh()
    }

    fun setCutoff(option: CutoffOption) {
        savedCutoffJob.cancel()
        mutableState.update { it.copy(cutoff = option) }
        viewModelScope.launch {
            try {
                settings.setCutoff(option)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableMessage.value = UserMessage.CutoffNotSaved
            }
        }
        reload()
    }

    fun messageShown() {
        mutableMessage.value = null
    }

    fun toggleSelected(id: Long) =
        mutableState.update { current ->
            val selectedIds = if (id in current.selectedIds) current.selectedIds - id else current.selectedIds + id
            current.copy(selectedIds = selectedIds)
        }

    fun selectAll() = mutableState.update { current -> current.copy(selectedIds = current.tracks.map { it.id }.toSet()) }

    fun clearSelection() = mutableState.update { it.copy(selectedIds = emptySet()) }

    /**
     * Matches [selectedIds] (the ticks as they were when the user tapped, by default the current ones)
     * to phone files and opens the confirmation. A file is only removable when no other song in the whole
     * library points at it, so unticked songs never lose their file. Returns null when a preparation is
     * already running or failed; a failure sets [message] and trashes nothing.
     */
    suspend fun prepareTrash(selectedIds: Set<Long> = mutableState.value.selectedIds): TrashPlan? {
        if (!mutableIsPreparingTrash.compareAndSet(expect = false, update = true)) return null
        try {
            val selectedTracks = mutableState.value.tracks.filter { it.id in selectedIds }
            val selectedFileKeys = selectedTracks.map { it.folderPath to it.fileName }.toSet()
            val sharingTracks = library
                .tracks(LibraryList.AllTracks, mutableState.value.cutoff.period, clock.instant())
                .filter { (it.folderPath to it.fileName) in selectedFileKeys }
            val tracksToMatch = (selectedTracks + sharingTracks).distinctBy { it.id }
            val files = matcher.match(tracksToMatch).filter { it.track.id in selectedIds }
            val removable = files.filterIsInstance<TrackFile.Removable>()
            val plan = TrashPlan(
                removable = removable,
                notRemovable = files.filterIsInstance<TrackFile.NotRemovable>(),
                totalSizeBytes = removable.sumOf { it.sizeBytes },
                batches = trashBatches(removable),
            )
            mutableTrashFlow.value = TrashFlow.Confirming(plan)
            return plan
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            mutableMessage.value = UserMessage.TrashPreparationFailed
            return null
        } finally {
            mutableIsPreparingTrash.value = false
        }
    }

    fun confirmTrash() {
        val confirming = mutableTrashFlow.value as? TrashFlow.Confirming ?: return
        advanceTrash(confirming.plan.batches, movedCount = 0, attemptedCount = confirming.plan.removable.size)
    }

    fun cancelTrash() {
        if (mutableTrashFlow.value is TrashFlow.Confirming) mutableTrashFlow.value = TrashFlow.Idle
    }

    /** Returns the current batch the first time it is asked for, so a recreated screen never relaunches it. */
    fun takeBatchToLaunch(): List<TrackFile.Removable>? {
        val inProgress = mutableTrashFlow.value as? TrashFlow.InProgress ?: return null
        if (!isCurrentBatchAwaitingLaunch) return null
        isCurrentBatchAwaitingLaunch = false
        return inProgress.currentBatch
    }

    fun onBatchResult(resultOk: Boolean) {
        val inProgress = mutableTrashFlow.value as? TrashFlow.InProgress ?: return
        val movedCount = inProgress.movedCount + if (resultOk) inProgress.currentBatch.size else 0
        advanceTrash(inProgress.remainingBatches, movedCount, inProgress.attemptedCount)
    }

    fun finishTrash() {
        if (mutableTrashFlow.value !is TrashFlow.Done) return
        mutableTrashFlow.value = TrashFlow.Idle
        refresh()
    }

    private fun advanceTrash(batches: List<List<TrackFile.Removable>>, movedCount: Int, attemptedCount: Int) {
        if (batches.isEmpty()) {
            mutableTrashFlow.value = TrashFlow.Done(movedCount, attemptedCount)
            access.requestRescan()
        } else {
            isCurrentBatchAwaitingLaunch = true
            mutableTrashFlow.value = TrashFlow.InProgress(batches.first(), batches.drop(1), movedCount, attemptedCount)
        }
    }

    fun requestPermission() = access.requestPermission()

    fun openPlayer() = access.openPlayer()

    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        savedCutoffJob.join()
        mutableState.update { it.copy(isLoading = true, loadFailed = false, selectedIds = emptySet()) }
        val current = mutableState.value
        var accessRead: AccessState? = null
        try {
            val accessState = access.state()
            accessRead = accessState
            if (accessState != AccessState.Ready) {
                showEmpty(accessState)
                return
            }
            val now = clock.instant()
            val playerTracks = library.tracks(LibraryList.AllTracks, current.cutoff.period, now)
            val entries = history.record(playerTracks)
            val allTracks = mergeHistory(playerTracks, entries)
            val summary = historySummary(allTracks, entries)
            val health = historyHealth(
                earliestFirstSeenAt = summary.earliestFirstSeenAt,
                totalTracks = summary.totalTracks,
                unplayedTracks = summary.unplayedTracks,
                cutoff = current.cutoff.period,
                now = now,
            )
            val content = loadScreenContent(current, summary, allTracks, now)
            mutableState.update {
                it.copy(
                    access = accessState,
                    tracks = content.tracks,
                    home = content.home,
                    sizesById = content.sizesById,
                    sizesUnavailable = content.sizesUnavailable,
                    historyHealth = health,
                    selectedIds = emptySet(),
                    isLoading = false,
                )
            }
        } catch (_: SecurityException) {
            showEmpty(AccessState.NeedsPermission)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            mutableState.update {
                it.copy(access = accessRead ?: it.access, tracks = emptyList(), home = null, sizesById = emptyMap(), sizesUnavailable = false, selectedIds = emptySet(), isLoading = false, loadFailed = true)
            }
        }
    }

    private class ScreenContent(
        val tracks: List<PlayerTrack>,
        val home: HomeSummary?,
        val sizesById: Map<Long, Long>,
        val sizesUnavailable: Boolean = false,
    )

    private suspend fun loadScreenContent(current: LibraryUiState, summary: HistorySummary, allTracks: List<PlayerTrack>, now: Instant): ScreenContent {
        val period = current.cutoff.period
        return when (current.screen) {
            Screen.Home -> ScreenContent(
                tracks = emptyList(),
                home = homeSummary(
                    history = summary,
                    mostPlayed = tracksFor(LibraryList.MostPlayed, allTracks, period, now),
                    deleteCandidates = tracksFor(LibraryList.DeleteCandidates, allTracks, period, now),
                    allTracks = allTracks,
                    sizes = sizesOrNull(current.hasFileAccess),
                ),
                sizesById = emptyMap(),
            )
            // Sizes and tracks come from two providers, so the large songs are filtered here instead of in SQL.
            Screen.Large -> {
                val sizes = sizesOrNull(current.hasFileAccess)
                val largeSongList = largeSongs(allTracks, sizes.orEmpty())
                ScreenContent(
                    tracks = largeSongList.map { (track, _) -> track },
                    home = null,
                    sizesById = largeSongList.associate { (track, sizeBytes) -> track.id to sizeBytes },
                    sizesUnavailable = sizes == null,
                )
            }
            Screen.Settings -> ScreenContent(emptyList(), null, emptyMap())
            Screen.MostPlayed -> listContent(LibraryList.MostPlayed, allTracks, period, now)
            Screen.NeverPlayed -> listContent(LibraryList.NeverPlayed, allTracks, period, now)
            Screen.Delete -> listContent(LibraryList.DeleteCandidates, allTracks, period, now)
        }
    }

    /** Sizes are display only, so a failed read hides them instead of failing the screen. */
    private suspend fun sizesOrNull(hasFileAccess: Boolean): Map<FileLocation, Long>? =
        if (!hasFileAccess) {
            null
        } else {
            try {
                fileSizes.sizesByLocation()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
        }

    private fun listContent(list: LibraryList, allTracks: List<PlayerTrack>, period: Period, now: Instant) =
        ScreenContent(tracksFor(list, allTracks, period, now), null, emptyMap())

    private fun Screen.libraryList(): LibraryList? =
        when (this) {
            Screen.MostPlayed -> LibraryList.MostPlayed
            Screen.NeverPlayed -> LibraryList.NeverPlayed
            Screen.Delete -> LibraryList.DeleteCandidates
            Screen.Large -> LibraryList.Large
            Screen.Home, Screen.Settings -> null
        }

    private fun showEmpty(accessState: AccessState) =
        mutableState.update { it.copy(access = accessState, tracks = emptyList(), home = null, sizesById = emptyMap(), sizesUnavailable = false, selectedIds = emptySet(), isLoading = false) }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(
                library = PlayerLibrary(application.contentResolver),
                history = RoomPlayHistory(PlayHistoryDatabase.open(application).playHistoryDao(), Clock.systemUTC()),
                access = PlayerAccess(application),
                matcher = TrackFileMatcher(application.contentResolver),
                fileSizes = MediaStoreFileSizes(application.contentResolver),
                settings = CutoffSettings(application.settingsDataStore),
                clock = Clock.systemUTC(),
            ) as T
        }
    }
}
