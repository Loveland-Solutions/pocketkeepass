package mn.lls.pocketkeepass.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mn.lls.pocketkeepass.DatabaseState
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.UnlockError
import mn.lls.pocketkeepass.files.Backup
import mn.lls.pocketkeepass.files.Document
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import mn.lls.pocketkeepass.security.hideTextFromAccessibility
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

/**
 * Asks for the master password and/or key file of the chosen database.
 *
 * Without [onChangeDatabase], [onAddKeyFile], or [onRemoveKeyFile], that choice isn't offered:
 * the screen autofill shows on top of another app only unlocks. The same goes for restoring the
 * backup ([onRestoreBackup], [onSaveBackupAs]) when the file is missing or damaged.
 */
@Composable
fun UnlockScreen(
    state: DatabaseState.Locked,
    password: TextFieldState,
    onUnlock: () -> Unit,
    onChangeDatabase: (() -> Unit)?,
    onAddKeyFile: (() -> Unit)?,
    onRemoveKeyFile: (() -> Unit)?,
    onQuickUnlock: () -> Unit,
    onOfferQuickUnlock: () -> Unit,
    onRestoreBackup: (() -> Unit)? = null,
    onSaveBackupAs: (() -> Unit)? = null,
) {
    val enabled = !state.unlocking && !state.restoring
    var confirmRestore by remember { mutableStateOf(false) }
    // Ask for biometrics straight away, once per lock; the ViewModel keeps track of "once".
    LaunchedEffect(state.database, state.quickUnlock) {
        if (state.quickUnlock) onOfferQuickUnlock()
    }
    var showPassword by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // The field is disabled while unlocking, which takes its focus away. Give it back afterwards,
    // so a failed attempt goes straight back to typing.
    LaunchedEffect(state.database, state.unlocking) {
        if (!state.unlocking) focus.requestFocus()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.database.name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onChangeDatabase != null) {
                    TextButton(onClick = onChangeDatabase, enabled = enabled) {
                        Text(stringResource(R.string.change_database))
                    }
                }
            }

            OutlinedSecureTextField(
                state = password,
                enabled = enabled,
                label = { Text(stringResource(R.string.master_password)) },
                textObfuscationMode = if (showPassword) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            contentDescription = stringResource(if (showPassword) R.string.hide_password else R.string.show_password),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                onKeyboardAction = { onUnlock() },
                isError = state.error != null,
                modifier = Modifier
                    .hideTextFromAccessibility(password, shown = showPassword)
                    .fillMaxWidth()
                    .focusRequester(focus),
            )

            val keyFile = state.keyFile
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (keyFile == null) {
                    if (onAddKeyFile != null) {
                        TextButton(onClick = onAddKeyFile, enabled = enabled) {
                            Text(stringResource(R.string.add_key_file))
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.key_file, keyFile.name),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onRemoveKeyFile != null) {
                        TextButton(onClick = onRemoveKeyFile, enabled = enabled) {
                            Text(stringResource(R.string.remove_key_file))
                        }
                    }
                }
            }

            state.error?.let { error ->
                Text(error.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            val backup = state.backup
            if (backup != null && onRestoreBackup != null && onSaveBackupAs != null) {
                Text(
                    stringResource(
                        if (backup.unfinished) R.string.backup_unfinished_available else R.string.backup_available,
                        backupTime(backup),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmRestore = true }, enabled = enabled) {
                        Text(stringResource(R.string.restore_backup))
                    }
                    TextButton(onClick = onSaveBackupAs, enabled = enabled) {
                        Text(stringResource(R.string.save_backup_as))
                    }
                }
                if (state.restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.restored) {
                Text(stringResource(R.string.backup_restored), style = MaterialTheme.typography.bodyMedium)
            }

            Button(onClick = onUnlock, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                if (state.unlocking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.unlock))
                }
            }
            if (state.quickUnlock) {
                OutlinedButton(onClick = onQuickUnlock, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unlock_with_biometrics))
                }
            }
            state.passwordDueDays?.let { days ->
                Text(
                    pluralStringResource(R.plurals.biometric_password_due, days, days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.unlocking) {
                Text(
                    stringResource(R.string.unlocking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val backup = state.backup
    if (confirmRestore && backup != null && onRestoreBackup != null) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.restore_backup_title)) },
            text = {
                AccessibleToToolsOnly() // a dialog is a window of its own
                Text(stringResource(R.string.restore_backup_message, state.database.name, backupTime(backup)))
            },
            confirmButton = {
                TextButton(onClick = { confirmRestore = false; onRestoreBackup() }) {
                    Text(stringResource(R.string.restore_backup_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** When the backup was taken, as the phone writes dates and times. */
@Composable
private fun backupTime(backup: Backup): String {
    val context = LocalContext.current
    return remember(backup) {
        DateUtils.formatDateTime(context, backup.savedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
    }
}

@Composable
private fun UnlockError.message(): String = when (this) {
    is UnlockError.Engine -> message
    is UnlockError.CantRead -> stringResource(if (keyFile) R.string.cant_read_key_file else R.string.cant_read_database)
    is UnlockError.Biometric -> message
    UnlockError.QuickUnlockInvalidated -> stringResource(R.string.biometric_invalidated)
    UnlockError.QuickUnlockOutdated -> stringResource(R.string.biometric_outdated)
    UnlockError.CantRestore -> stringResource(R.string.cant_restore_backup)
    UnlockError.CantSaveBackup -> stringResource(R.string.cant_save_backup)
}

@Preview(showBackground = true)
@Composable
private fun UnlockScreenPreview() {
    PocketKeePassTheme {
        UnlockScreen(
            state = DatabaseState.Locked(
                database = Document(Uri.EMPTY, "Passwords.kdbx"),
                keyFile = Document(Uri.EMPTY, "secret.keyx"),
                error = UnlockError.Engine("The password or key file is incorrect."),
            ),
            password = TextFieldState(),
            onUnlock = {},
            onChangeDatabase = {},
            onAddKeyFile = {},
            onRemoveKeyFile = {},
            onQuickUnlock = {},
            onOfferQuickUnlock = {},
        )
    }
}
