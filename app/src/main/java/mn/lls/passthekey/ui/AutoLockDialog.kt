package mn.lls.passthekey.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import mn.lls.passthekey.R
import mn.lls.passthekey.security.AutoLockSettings

/** Chooses when the open database locks itself. Changes apply straight away. */
@Composable
fun AutoLockDialog(settings: AutoLockSettings, onChange: (AutoLockSettings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        title = { Text(stringResource(R.string.auto_lock)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.auto_lock_timeout), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup()) {
                    for (seconds in AutoLockSettings.TIMEOUTS) {
                        val selected = seconds == settings.timeoutSeconds
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected, role = Role.RadioButton) { onChange(settings.copy(timeoutSeconds = seconds)) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(duration(seconds), Modifier.padding(start = 12.dp))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(settings.onScreenOff, role = Role.Switch) { onChange(settings.copy(onScreenOff = it)) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.auto_lock_screen_off), Modifier.weight(1f))
                    Switch(checked = settings.onScreenOff, onCheckedChange = null)
                }
            }
        },
    )
}

@Composable
private fun duration(seconds: Int): String = when {
    seconds % 3600 == 0 -> pluralStringResource(R.plurals.hours, seconds / 3600, seconds / 3600)
    seconds % 60 == 0 -> pluralStringResource(R.plurals.minutes, seconds / 60, seconds / 60)
    else -> pluralStringResource(R.plurals.seconds, seconds, seconds)
}
