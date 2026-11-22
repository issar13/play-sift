package app.playsift.spotify

import app.playsift.player.PlayerTrack
import kotlin.math.abs

/** The picker shows at most this many Spotify tracks for one song. */
const val MAX_MATCH_OPTIONS = 8

private val bracketedNotes = Regex("[(\\[{][^)\\]}]*[)\\]}]")
private val featuringTail = Regex("\\s*\\b(?:feat|ft|featuring)\\b\\.?.*$", RegexOption.IGNORE_CASE)
private val repeatedSpaces = Regex("\\s+")

/** The exact-field query used for automatic matching: `track:"title" artist:"artist"`. */
fun strictQuery(title: String, artist: String): String = "track:\"${title.withoutQuotes()}\" artist:\"${artist.withoutQuotes()}\""

private fun String.withoutQuotes() = replace("\"", "")

/** Text without bracketed notes such as "(Remastered)", without "feat." and whatever follows it, and without stray spaces. */
fun looseText(text: String): String =
    text.withoutQuotes().replace(bracketedNotes, " ").replace(featuringTail, "").replace(repeatedSpaces, " ").trim()

/** The three searches for one song, most precise first: strict fields, then loose "title artist", then the title alone. */
fun optionQueries(track: PlayerTrack): List<String> {
    val looseTitle = looseText(track.title).ifBlank { track.title.withoutQuotes().trim() }
    val looseArtist = looseText(track.artist)
    return listOf(strictQuery(track.title, track.artist), "$looseTitle $looseArtist".trim(), looseTitle).distinct()
}

/** Finds several Spotify tracks for a song the automatic match could not decide, for the user to choose from. */
class OptionSearch(private val api: SpotifyApi) {
    /** Runs the searches in order, stopping once there are [MAX_MATCH_OPTIONS] distinct tracks (by Spotify id). */
    suspend fun optionsFor(track: PlayerTrack): List<SpotifyTrack> = collect(optionQueries(track), emptyList())

    private tailrec suspend fun collect(queries: List<String>, found: List<SpotifyTrack>): List<SpotifyTrack> {
        if (found.size >= MAX_MATCH_OPTIONS || queries.isEmpty()) return found.take(MAX_MATCH_OPTIONS)
        return collect(queries.drop(1), (found + api.searchOptions(queries.first())).distinctBy { it.id })
    }
}

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

/** Minutes and seconds as m:ss, for example 72000 gives 1:12. */
fun lengthText(millis: Long): String {
    val totalSeconds = millis / MILLIS_PER_SECOND
    return "${totalSeconds / SECONDS_PER_MINUTE}:${(totalSeconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

/** "1:12 longer" or "0:20 shorter" when the Spotify track differs from the local file by more than [DURATION_TOLERANCE_MILLIS]; null otherwise or when the local length is unknown. */
fun lengthHint(localMillis: Long, optionMillis: Long): String? {
    val difference = optionMillis - localMillis
    return when {
        localMillis <= 0 || abs(difference) <= DURATION_TOLERANCE_MILLIS -> null
        difference > 0 -> "${lengthText(difference)} longer"
        else -> "${lengthText(-difference)} shorter"
    }
}
