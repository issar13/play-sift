package app.playsift.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Lets a test read which container colour a card was given. */
internal val CardContainerColor = SemanticsPropertyKey<Color>("CardContainerColor")
private var SemanticsPropertyReceiver.cardContainerColor by CardContainerColor

private val historyDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneOffset.UTC)

@Composable
fun HomeScreen(
    state: LibraryUiState,
    onOpenDelete: () -> Unit,
    onOpenLarge: () -> Unit,
    onAllowFileAccess: () -> Unit,
    onImportHistory: () -> Unit = {},
    onDismissImportOffer: () -> Unit = {},
) {
    val summary = state.home ?: return
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.showImportOffer) ImportOfferBanner(onImportHistory, onDismissImportOffer)
        HeaderBand(summary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(
                title = "Played",
                value = summary.playedSongs.toString(),
                detail = null,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                testTag = "playedCard",
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                title = "Never played",
                value = summary.neverPlayedSongs.toString(),
                detail = null,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                testTag = "neverPlayedCard",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(
                title = "Delete candidates",
                value = summary.deleteCandidates.toString(),
                detail = summary.deleteCandidatesSizeBytes?.let { sizeBytes -> "${megabytesText(sizeBytes)} in known files" },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                testTag = "deleteCard",
                modifier = Modifier.weight(1f),
                onClick = onOpenDelete,
            )
            SummaryCard(
                title = "Large songs 5 MB+",
                value = summary.largeSongs?.toString() ?: if (state.hasFileAccess) "Sizes unavailable" else "Allow file access",
                detail = summary.largeSongsSizeBytes?.let(::megabytesText),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                testTag = "largeCard",
                modifier = Modifier.weight(1f),
                onClick = if (state.hasFileAccess) onOpenLarge else onAllowFileAccess,
            )
        }
        Highlights(summary)
    }
}

@Composable
private fun HeaderBand(summary: HomeSummary) {
    val playedFraction = if (summary.totalSongs == 0) 0f else summary.playedSongs.toFloat() / summary.totalSongs
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (summary.totalSongs == 1) "1 song" else "${summary.totalSongs} songs",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            summary.librarySizeBytes?.let { librarySizeBytes ->
                Text("${megabytesText(librarySizeBytes)} in known files", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            LinearProgressIndicator(progress = { playedFraction }, modifier = Modifier.fillMaxWidth())
            Text(
                text = "${summary.playedSongs} played, ${summary.neverPlayedSongs} never played",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    detail: String?,
    containerColor: Color,
    contentColor: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val tappable = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        modifier = tappable.testTag(testTag).semantics { cardContainerColor = containerColor },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge)
            detail?.let { detailText -> Text(detailText, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Highlights(summary: HomeSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Highlights", style = MaterialTheme.typography.titleMedium)
        summary.mostPlayed?.let { track ->
            Text("Most played song: ${track.title} by ${track.artist} (${pluralPlays(track.playCount)})")
        }
        summary.topArtist?.let { artistPlays ->
            Text("Top artist: ${artistPlays.artist} (${pluralPlays(artistPlays.plays)})")
        }
        summary.trackingSince?.let { trackingSince ->
            Text("Tracking since ${historyDateFormat.format(trackingSince)}")
        }
    }
}

private fun pluralPlays(plays: Int) = if (plays == 1) "1 play" else "$plays plays"

@Composable
private fun ImportOfferBanner(onImport: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Play Sift has no saved history yet. If you saved a play-history file from an earlier install, import it to bring your play counts back.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport) { Text("Import history") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
