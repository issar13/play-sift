package app.playsift.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.showImportOffer) ImportOfferBanner(onImportHistory, onDismissImportOffer)
        HeaderBand(summary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            SummaryCard(
                title = "Played",
                value = summary.playedSongs.toString(),
                detail = null,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                testTag = "playedCard",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            SummaryCard(
                title = "Never played",
                value = summary.neverPlayedSongs.toString(),
                detail = null,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                testTag = "neverPlayedCard",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            SummaryCard(
                title = "Delete candidates",
                value = summary.deleteCandidates.toString(),
                detail = summary.deleteCandidatesSizeBytes?.let { sizeBytes -> "${storageText(sizeBytes)} in known files" },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                testTag = "deleteCard",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = onOpenDelete,
            )
            SummaryCard(
                title = "Large songs 10 MB+",
                value = summary.largeSongs?.toString() ?: if (state.hasFileAccess) "Sizes unavailable" else "Allow file access",
                detail = summary.largeSongsSizeBytes?.let(::storageText),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                testTag = "largeCard",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = if (state.hasFileAccess) onOpenLarge else onAllowFileAccess,
            )
        }
        Highlights(summary)
    }
}

@Composable
private fun HeaderBand(summary: HomeSummary) {
    val playedFraction = if (summary.totalSongs == 0) 0f else summary.playedSongs.toFloat() / summary.totalSongs
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (summary.totalSongs == 1) "1 song" else "${summary.totalSongs} songs",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            summary.librarySizeBytes?.let { librarySizeBytes ->
                Text(
                    text = "${storageText(librarySizeBytes)} in known files",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            summary.trackingSince?.let { trackingSince ->
                Text(
                    text = "Tracking since ${historyDateFormat.format(trackingSince)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { playedFraction },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Text(
                text = "${summary.playedSongs} played, ${summary.neverPlayedSongs} never played",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        modifier = tappable.testTag(testTag).semantics { cardContainerColor = containerColor },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            detail?.let { detailText -> Text(detailText, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Highlights(summary: HomeSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text("Highlights", style = MaterialTheme.typography.titleLarge)
        summary.mostPlayed?.let { track ->
            HighlightRow(label = "Most played song", name = track.title, detail = "${track.artist} \u00b7 ${pluralPlays(track.playCount)}", colorSeed = track.artist)
        }
        summary.topArtist?.let { artistPlays ->
            HighlightRow(label = "Top artist", name = artistPlays.artist, detail = pluralPlays(artistPlays.plays), colorSeed = artistPlays.artist)
        }
    }
}

@Composable
private fun HighlightRow(label: String, name: String, detail: String, colorSeed: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ArtworkTile(label = name, colorSeed = colorSeed, size = 56.dp)
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun pluralPlays(plays: Int) = if (plays == 1) "1 play" else "$plays plays"

@Composable
private fun ImportOfferBanner(onImport: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Play Sift has no saved history yet. If you saved a play-history file from an earlier install, import it to bring your play counts back.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport) { Text("Import history") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
