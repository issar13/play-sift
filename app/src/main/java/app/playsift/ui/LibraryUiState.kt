package app.playsift.ui

import app.playsift.files.TrackFile
import app.playsift.player.AccessState
import app.playsift.player.HistoryHealth
import app.playsift.player.LibraryList
import app.playsift.player.PlayerTrack
import app.playsift.settings.CutoffOption

data class LibraryUiState(
    val access: AccessState,
    val list: LibraryList,
    val tracks: List<PlayerTrack>,
    val historyHealth: HistoryHealth,
    val cutoff: CutoffOption,
    val selectedIds: Set<Long>,
    val isLoading: Boolean,
    val loadFailed: Boolean = false,
)

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
