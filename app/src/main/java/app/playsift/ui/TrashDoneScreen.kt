package app.playsift.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.playsift.R

@Composable
fun TrashDoneScreen(
    movedCount: Int,
    attemptedCount: Int,
    onOpenPlayer: () -> Unit,
    onFinished: () -> Unit,
) {
    BackHandler(onBack = onFinished)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(pluralStringResource(R.plurals.trash_done_summary, attemptedCount, movedCount, attemptedCount))
        Text("If they still show in your music player, rescan there.", textAlign = TextAlign.Center)
        Button(onClick = onOpenPlayer) { Text("Open music player") }
        OutlinedButton(onClick = onFinished) { Text("Done") }
    }
}
