package mn.lls.pocketkeepass.ui

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
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import mn.lls.pocketkeepass.security.AutoLockSettings
import mn.lls.pocketkeepass.security.Clipboard
import mn.lls.pocketkeepass.security.QuickUnlockSetting

/**
 * Chooses when the open database locks itself, when the clipboard is cleared, and whether
 * biometrics can unlock the database. Changes apply straight away. Ends with the credits.
 */
@Composable
fun SettingsDialog(
    settings: AutoLockSettings,
    onChange: (AutoLockSettings) -> Unit,
    clipboardTimeoutSeconds: Int,
    onChangeClipboardTimeout: (Int) -> Unit,
    quickUnlock: QuickUnlockSetting,
    onChangeQuickUnlock: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        title = { Text(stringResource(R.string.settings)) },
        text = {
            AccessibleToToolsOnly() // a dialog is a window of its own
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.biometric_unlock), style = MaterialTheme.typography.titleSmall)
                val available = quickUnlock != QuickUnlockSetting.Unavailable
                val on = quickUnlock == QuickUnlockSetting.On
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(on, enabled = available, role = Role.Switch, onValueChange = onChangeQuickUnlock)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.biometric_unlock_switch), Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = null, enabled = available)
                }
                Text(
                    stringResource(if (available) R.string.biometric_unlock_explained else R.string.biometric_unlock_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.auto_lock), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.auto_lock_timeout), style = MaterialTheme.typography.bodyMedium)
                Choices(AutoLockSettings.TIMEOUTS, settings.timeoutSeconds) { onChange(settings.copy(timeoutSeconds = it)) }
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
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.clipboard), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.clipboard_timeout), style = MaterialTheme.typography.bodyMedium)
                Choices(Clipboard.TIMEOUTS, clipboardTimeoutSeconds, onChangeClipboardTimeout)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.about), style = MaterialTheme.typography.titleSmall)
                for (text in listOf(R.string.about_keepass, R.string.about_claude, R.string.about_license)) {
                    Text(
                        stringResource(text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** Radio buttons for a list of durations, in seconds. */
@Composable
private fun Choices(choices: List<Int>, selectedSeconds: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for (seconds in choices) {
            val selected = seconds == selectedSeconds
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected, role = Role.RadioButton) { onSelect(seconds) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(duration(seconds), Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun duration(seconds: Int): String = when {
    seconds % 3600 == 0 -> pluralStringResource(R.plurals.hours, seconds / 3600, seconds / 3600)
    seconds % 60 == 0 -> pluralStringResource(R.plurals.minutes, seconds / 60, seconds / 60)
    else -> pluralStringResource(R.plurals.seconds, seconds, seconds)
}
