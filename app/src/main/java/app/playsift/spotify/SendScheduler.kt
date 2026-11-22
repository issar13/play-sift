package app.playsift.spotify

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

sealed interface SendWorkState {
    data object Idle : SendWorkState

    /** [total] is 0 until the songs are read. */
    data class Matching(val done: Int, val total: Int) : SendWorkState

    data object Publishing : SendWorkState
}

enum class StartOutcome { Started, AlreadyRunning }

/** Starts, watches and stops the one send that may run at a time. */
interface SendScheduler {
    val state: Flow<SendWorkState>

    suspend fun start(request: SendRequest): StartOutcome

    fun cancel()
}

const val SEND_UNIQUE_WORK_NAME = "spotify-send"
const val PROGRESS_DONE = "done"
const val PROGRESS_TOTAL = "total"
const val PROGRESS_PUBLISHING = "publishing"

class WorkManagerSendScheduler(private val workManager: WorkManager, private val store: SendStore) : SendScheduler {
    override val state: Flow<SendWorkState> =
        workManager.getWorkInfosForUniqueWorkFlow(SEND_UNIQUE_WORK_NAME).map { infos ->
            infos.firstOrNull { !it.state.isFinished }?.let(::stateOf) ?: SendWorkState.Idle
        }

    /** Refuses while a send is queued or running; the request is saved first because ticked songs do not fit in work input data. */
    override suspend fun start(request: SendRequest): StartOutcome {
        if (state.first() != SendWorkState.Idle) return StartOutcome.AlreadyRunning
        store.clearSaved()
        store.saveRequest(request)
        val work = OneTimeWorkRequestBuilder<SpotifySendWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(SEND_UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, work).await()
        return StartOutcome.Started
    }

    override fun cancel() {
        workManager.cancelUniqueWork(SEND_UNIQUE_WORK_NAME)
    }

    private fun stateOf(info: WorkInfo): SendWorkState =
        when {
            info.progress.getBoolean(PROGRESS_PUBLISHING, false) -> SendWorkState.Publishing
            else -> SendWorkState.Matching(info.progress.getInt(PROGRESS_DONE, 0), info.progress.getInt(PROGRESS_TOTAL, 0))
        }
}
