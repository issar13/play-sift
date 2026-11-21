package app.playsift.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import app.playsift.R
import app.playsift.files.NotRemovableReason
import java.util.Locale

private const val BYTES_PER_MEGABYTE = 1_000_000.0
private val NOT_REMOVABLE_LIST_MAX_HEIGHT = 200.dp

@Composable
fun TrashConfirmDialog(
    plan: TrashPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val songCount = plan.removable.size
    val sizeText = String.format(Locale.ROOT, "%.1f MB", plan.totalSizeBytes / BYTES_PER_MEGABYTE)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = songCount > 0) { Text("Move to trash") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                Text(pluralStringResource(R.plurals.trash_confirm_question, songCount, songCount, sizeText))
                if (plan.notRemovable.isNotEmpty()) {
                    Text("Can't remove from here")
                    LazyColumn(modifier = Modifier.heightIn(max = NOT_REMOVABLE_LIST_MAX_HEIGHT)) {
                        items(plan.notRemovable, key = { it.track.id }) { notRemovable ->
                            Text("${notRemovable.track.title}: ${reasonLabel(notRemovable.reason)}")
                        }
                    }
                }
            }
        },
    )
}

private fun reasonLabel(reason: NotRemovableReason) = when (reason) {
    NotRemovableReason.CueTrack -> "Part of a cue sheet"
    NotRemovableReason.StreamOrProvider -> "Stream or added link"
    NotRemovableReason.NotOnInternalStorage -> "Not on internal storage"
    NotRemovableReason.NotFoundOnPhone -> "File not found on phone"
    NotRemovableReason.AmbiguousMatch -> "More than one matching file"
}
