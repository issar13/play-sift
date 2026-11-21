package app.playsift.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.playsift.settings.CutoffOption

@Composable
fun SettingsScreen(selected: CutoffOption, onSelect: (CutoffOption) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Never played for at least", style = MaterialTheme.typography.titleMedium)
        CutoffOption.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Text(text = option.label, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}
