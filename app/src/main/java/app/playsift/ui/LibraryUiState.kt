package app.playsift.ui

import app.playsift.files.TrackFile
import app.playsift.player.AccessState
import app.playsift.player.HistoryHealth
import app.playsift.player.LibraryList
import app.playsift.player.PlayerTrack
import app.playsift.settings.CutoffOption

enum class Screen { Home, MostPlayed, NeverPlayed, Delete, Large, Settings }

data class LibraryUiState(
    val access: AccessState,
    val list: LibraryList,
    val tracks: List<PlayerTrack>,
    val historyHealth: HistoryHealth,
    val cutoff: CutoffOption,
    val selectedIds: Set<Long>,
    val isLoading: Boolean,
    val loadFailed: Boolean = false,
    val screen: Screen = Screen.Home,
    val home: HomeSummary? = null,
    val sizesById: Map<Long, Long> = emptyMap(),
    val hasFileAccess: Boolean = false,
    val sizesUnavailable: Boolean = false,
)

enum class UserMessage(val text: String) {
    TrashPreparationFailed("Couldn't check your files. Nothing was moved to the trash. Try again."),
    CutoffNotSaved("Couldn't save your cutoff. It will reset when you reopen the app."),
    HistoryExported("History saved to Documents/PlaySift."),
    HistoryExportFailed("Couldn't save the history file. Try again."),
    HistoryImported("History imported."),
    HistoryImportFailed("Couldn't read that history file. Nothing was changed."),
}

data class TrashPlan(
    val removable: List<TrackFile.Removable>,
    val notRemovable: List<TrackFile.NotRemovable>,
    val totalSizeBytes: Long,
    val batches: List<List<TrackFile.Removable>>,
)

sealed interface TrashFlow {
    data object Idle : TrashFlow

    data class Confirming(val plan: TrashPlan) : TrashFlow

    data class InProgress(
        val currentBatch: List<TrackFile.Removable>,
        val remainingBatches: List<List<TrackFile.Removable>>,
        val movedCount: Int,
        val attemptedCount: Int,
    ) : TrashFlow

    data class Done(val movedCount: Int, val attemptedCount: Int) : TrashFlow
}
