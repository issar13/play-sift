package app.playsift.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import app.playsift.MainActivity
import app.playsift.R
import java.text.NumberFormat
import java.util.UUID

const val ACTION_OPEN_SPOTIFY = "app.playsift.OPEN_SPOTIFY"
const val SEND_CHANNEL_ID = "spotify-sending"
const val SEND_PROGRESS_NOTIFICATION_ID = 4101
const val SEND_FINISHED_NOTIFICATION_ID = 4102

/** The ongoing "Sending to Spotify" notification, with Cancel, and the one that replaces it when the send ends. */
class SendNotifications(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val numbers = NumberFormat.getIntegerInstance()

    fun foregroundInfo(workId: UUID, state: SendWorkState): ForegroundInfo =
        ForegroundInfo(SEND_PROGRESS_NOTIFICATION_ID, progressNotification(workId, state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun showFinished(saved: SavedSend) {
        ensureChannel()
        val builder = Notification.Builder(context, SEND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_spotify)
            .setContentTitle(if (saved.failureMessage == null) "Spotify send finished" else "Spotify send failed")
            .setContentText(finishedText(saved))
            .setContentIntent(openSpotifyIntent())
            .setAutoCancel(true)
        notificationManager.notify(SEND_FINISHED_NOTIFICATION_ID, builder.build())
    }

    private fun finishedText(saved: SavedSend): String =
        saved.failureMessage
            ?: with(saved.result) {
                val counts = "${numbers.format(added.size)} added, ${numbers.format(saved.unsure.size)} unsure, ${numbers.format(notFound.size)} not found"
                if (playlist == null) "No playlist yet: $counts" else "Playlist ready: $counts"
            }

    private fun progressNotification(workId: UUID, state: SendWorkState): Notification {
        ensureChannel()
        val builder = Notification.Builder(context, SEND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_spotify)
            .setContentTitle("Sending to Spotify")
            .setContentText(progressText(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSpotifyIntent())
            .addAction(Notification.Action.Builder(null, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(workId)).build())
        return when (state) {
            is SendWorkState.Matching -> builder.setProgress(state.total, state.done, state.total == 0)
            else -> builder.setProgress(0, 0, true)
        }.build()
    }

    private fun progressText(state: SendWorkState): String =
        when (state) {
            is SendWorkState.Matching ->
                if (state.total == 0) "Reading your songs" else "Matching ${numbers.format(state.done)} of ${numbers.format(state.total)}"
            is SendWorkState.WaitingForSpotify -> "Spotify asked Play Sift to wait. Carrying on in about ${minutesToWait(state.seconds)} min."
            else -> "Making the playlist"
        }

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(SEND_CHANNEL_ID, "Spotify sending", NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** MainActivity is singleTask, so this brings the running app forward and opens the Spotify tab. */
    private fun openSpotifyIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_SPOTIFY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
