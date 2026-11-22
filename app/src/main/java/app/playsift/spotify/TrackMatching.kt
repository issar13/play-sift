package app.playsift.spotify

import app.playsift.player.PlayerTrack
import java.text.Normalizer
import kotlin.math.abs

/** A song and a Spotify result count as the same length when they differ by at most this. */
const val DURATION_TOLERANCE_MILLIS = 5_000L

private const val TITLE_EQUAL_POINTS = 3
private const val TITLE_CONTAINED_POINTS = 1
private const val ARTIST_POINTS = 2
private const val DURATION_POINTS = 2
private const val UNSURE_MINIMUM_POINTS = 5

sealed interface MatchResult {
    data class Matched(val candidate: SpotifyTrack) : MatchResult

    /** Close enough to show the user, not close enough to trust. */
    data class Unsure(val candidate: SpotifyTrack) : MatchResult

    data object NotFound : MatchResult
}

private val diacriticMarks = Regex("\\p{Mn}+")
private val bracketedText = Regex("[(\\[{][^)\\]}]*[)\\]}]")
private val versionWords = Regex("\\b(?:live|remix|mix|acoustic|instrumental|demo|edit|version|cover|karaoke|sped up|slowed|reverb)\\b")
private val nonLettersOrDigits = Regex("[^\\p{L}\\p{N}]+")
private val artistSeparators = Regex("\\s*(?:,|&|;|/|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\band\\b|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)

/** Lower case, no accents, no bracketed notes such as "(Remastered)" and no punctuation, so "Beyoncé - Halo" equals "beyonce halo". */
fun normalise(text: String): String =
    withoutAccents(text)
        .replace(bracketedText, " ")
        .replace(nonLettersOrDigits, " ")
        .trim()

private fun withoutAccents(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(diacriticMarks, "").lowercase()

/** Words such as "live" or "remix", inside brackets or not, that mark a different recording of the same title. */
private fun versionMarkers(title: String): Set<String> =
    versionWords.findAll(withoutAccents(title).replace(nonLettersOrDigits, " ")).map { it.value }.toSet()

private fun artistNames(artistText: String): List<String> =
    artistText.split(artistSeparators).map(::normalise).filter { it.isNotEmpty() }

private data class Score(val candidate: SpotifyTrack, val points: Int, val isExact: Boolean)

private fun score(track: PlayerTrack, candidate: SpotifyTrack): Score {
    val title = normalise(track.title)
    val candidateTitle = normalise(candidate.title)
    val isTitleEqual = title.isNotEmpty() && title == candidateTitle
    val isTitleContained = title.isNotEmpty() && candidateTitle.isNotEmpty() && (title in candidateTitle || candidateTitle in title)
    val localArtists = artistNames(track.artist) + normalise(track.artist)
    val candidateArtists = candidate.artists.map(::normalise)
    val isArtistMatch = localArtists.any { local -> candidateArtists.any { remote -> local == remote } }
    val isDurationMatch = track.durationMillis > 0 && abs(track.durationMillis - candidate.durationMillis) <= DURATION_TOLERANCE_MILLIS
    val points = (if (isTitleEqual) TITLE_EQUAL_POINTS else if (isTitleContained) TITLE_CONTAINED_POINTS else 0) +
        (if (isArtistMatch) ARTIST_POINTS else 0) +
        (if (isDurationMatch) DURATION_POINTS else 0)
    val isSameVersion = versionMarkers(track.title) == versionMarkers(candidate.title)
    return Score(candidate, points, isExact = isTitleEqual && isArtistMatch && isDurationMatch && isSameVersion)
}

/**
 * Matched needs the same normalised title, the same version words (live, remix and so on: a mismatch is at most Unsure), the same artist and a length within [DURATION_TOLERANCE_MILLIS].
 * Unsure needs at least two of the three (a contained title counts for less than an equal one), so a different
 * length or artist is shown to the user instead of added silently. Ties go to the result Spotify ranked first.
 */
fun matchTrack(track: PlayerTrack, candidates: List<SpotifyTrack>): MatchResult {
    val best = candidates.map { candidate -> score(track, candidate) }.maxWithOrNull(compareBy<Score> { it.isExact }.thenBy { it.points })
    return when {
        best == null -> MatchResult.NotFound
        best.isExact -> MatchResult.Matched(best.candidate)
        best.points >= UNSURE_MINIMUM_POINTS -> MatchResult.Unsure(best.candidate)
        else -> MatchResult.NotFound
    }
}
