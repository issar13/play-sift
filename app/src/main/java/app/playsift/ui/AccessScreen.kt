package app.playsift.ui

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.playsift.player.AccessState

@Composable
fun AccessScreen(
    access: AccessState,
    onRequestAccess: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = accessMessage(access), textAlign = TextAlign.Center)
        if (access == AccessState.NeedsPermission) {
            Button(onClick = onRequestAccess) { Text("Grant access") }
            OutlinedButton(onClick = onCheckAgain) { Text("Check again") }
        }
    }
}

private fun accessMessage(access: AccessState) = when (access) {
    AccessState.NotInstalled -> "Your music player isn't installed."
    AccessState.NeedsUpdate -> "Update your music player to use Play Sift."
    AccessState.NeedsPermission -> "Allow Play Sift to read your music player's library."
    AccessState.Ready -> ""
}
