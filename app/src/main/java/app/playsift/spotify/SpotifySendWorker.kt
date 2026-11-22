package app.playsift.spotify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/** What the worker uses; built once for the app and replaced by fakes in tests through a WorkerFactory. */
class SendServices(val runner: SendRunner, val store: SendStore, val notifications: SendNotifications)

/**
 * Runs one send as a foreground service of type dataSync, so it goes on with the app closed or the screen off.
 * Progress goes to WorkManager (the Spotify screen reads it) and to the notification. The trash step is never done here.
 */
class SpotifySendWorker(
    context: Context,
    parameters: WorkerParameters,
    private val services: SendServices,
) : CoroutineWorker(context, parameters) {
    /** WorkManager creates workers through this two-argument constructor. */
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, SpotifyComponents.of(context).sendServices)

    override suspend fun getForegroundInfo(): ForegroundInfo = services.notifications.foregroundInfo(id, SendWorkState.Matching(0, 0))

    override suspend fun doWork(): Result {
        val request = services.store.request() ?: return Result.failure()
        setForeground(getForegroundInfo())
        val saved = services.runner.run(request, onProgress = ::reportProgress, onPublishing = ::reportPublishing, onRateLimitWait = ::reportRateLimitWait)
        services.store.save(saved)
        services.notifications.showFinished(saved)
        return if (saved.failureMessage == null) Result.success() else Result.failure()
    }

    private suspend fun reportProgress(done: Int, total: Int) {
        setProgress(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
        setForeground(services.notifications.foregroundInfo(id, SendWorkState.Matching(done, total)))
    }

    private suspend fun reportPublishing() {
        setProgress(workDataOf(PROGRESS_PUBLISHING to true))
        setForeground(services.notifications.foregroundInfo(id, SendWorkState.Publishing))
    }

    /** The next [reportProgress] puts the "Matching" text back once the wait is over and a song is matched. */
    private suspend fun reportRateLimitWait(seconds: Long) {
        setForeground(services.notifications.foregroundInfo(id, SendWorkState.WaitingForSpotify(seconds)))
    }
}
