package app.playsift.history

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.playsift.player.PlayerAccess
import app.playsift.player.PlayerLibrary
import java.time.Clock
import java.util.concurrent.TimeUnit

class HistorySnapshotWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        HistorySnapshotter(
            access = PlayerAccess(applicationContext),
            library = PlayerLibrary(applicationContext.contentResolver),
            history = roomPlayHistory(applicationContext),
            clock = Clock.systemUTC(),
        ).snapshot()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "daily-history-snapshot"

        /** Daily runs catch a player reset before new plays hide it. Keeps an existing schedule untouched. */
        fun schedule(context: Context) =
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HistorySnapshotWorker>(1, TimeUnit.DAYS).build(),
            )
    }
}
