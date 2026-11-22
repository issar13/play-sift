package app.playsift.spotify

import app.playsift.player.PlayerTrack
import kotlinx.coroutines.CancellationException

const val NONE_MATCHED_MESSAGE = "None of these songs could be matched on Spotify, so no playlist was made."

/**
 * Matches the songs and makes the playlist from the ones that match for sure. Unsure songs are not decided here:
 * they are handed back for the user to add or skip later. Never touches the trash.
 */
class SendRunner(
    private val matcher: SongMatcher,
    private val publisher: PlaylistPublisher,
    private val loadMergedTracks: suspend () -> List<PlayerTrack>,
) {
    /** A failure becomes a [SavedSend] with a message; a cancellation is passed on so matches found so far stay cached. */
    suspend fun run(
        request: SendRequest,
        onProgress: suspend (done: Int, total: Int) -> Unit,
        onPublishing: suspend () -> Unit,
    ): SavedSend =
        try {
            send(request, onProgress, onPublishing)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            failedSend(request, failureText(exception))
        }

    private suspend fun send(request: SendRequest, onProgress: suspend (Int, Int) -> Unit, onPublishing: suspend () -> Unit): SavedSend {
        val source = sourceFor(request.choice, request.topCount)
        val mergedTracks = if (source == SendSource.TickedSongs) emptyList() else loadMergedTracks()
        val report = matcher.match(tracksFor(source, mergedTracks, request.tickedTracks), onProgress)
        if (report.matched.isEmpty() && report.unsure.isEmpty()) return failedSend(request, NONE_MATCHED_MESSAGE)
        val name = source.nameFor(request.playlistName)
        val playlist = report.matched.takeIf { it.isNotEmpty() }?.let { matched ->
            onPublishing()
            publisher.publish(source, name, uniqueUris(matched))
        }
        return SavedSend(
            choice = request.choice,
            topCount = request.topCount,
            playlistName = name,
            result = SendResult(playlist, report.matched, skippedUnsure = emptyList(), notFound = report.notFound),
            unsure = report.unsure,
        )
    }
}

fun failedSend(request: SendRequest, message: String) =
    SavedSend(
        choice = request.choice,
        topCount = request.topCount,
        playlistName = request.playlistName,
        result = SendResult(playlist = null, added = emptyList(), skippedUnsure = emptyList(), notFound = emptyList()),
        unsure = emptyList(),
        failureMessage = message,
    )

private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_FORBIDDEN = 403
private const val STATUS_TOO_MANY_REQUESTS = 429

private const val SIGNED_OUT_TEXT = "Spotify no longer accepts the sign-in. Sign out and sign in again."

/** A short reason the user can act on: signed out, no network, or Spotify refusing. */
fun failureText(exception: Exception): String =
    when {
        exception is SpotifyAuthException -> SIGNED_OUT_TEXT
        exception !is SpotifyHttpException -> "Something went wrong talking to Spotify. Check your connection and try again."
        exception.status == STATUS_UNAUTHORIZED -> SIGNED_OUT_TEXT
        exception.status == STATUS_FORBIDDEN ->
            "Spotify refused the request. Make sure your account is added to the app in the Spotify developer dashboard."
        exception.status == STATUS_TOO_MANY_REQUESTS -> "Spotify is busy right now. Try again in a minute."
        else -> "Spotify answered with an error (${exception.status}). Try again in a minute."
    }
