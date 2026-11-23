package mn.lls.pocketkeepass.ui

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.edit.TotpSetupState
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import mn.lls.pocketkeepass.security.hideTextFromAccessibility

/**
 * Setting up a one-time code: the user pastes the setup key (or `otpauth://` link) a site shows,
 * and sees the current code to enter there to finish. "Add" puts it in the entry's `otp` field;
 * it's saved with the entry.
 */
@Composable
fun TotpSetupDialog(
    state: TotpSetupState,
    onCheck: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Check as the user types or pastes. The CharSequence itself, never a String of it.
    LaunchedEffect(state.input) {
        snapshotFlow { state.input.text }.collect { onCheck() }
    }
    // A new code as each period starts, so the one shown is the one the site expects.
    LaunchedEffect(state.code, state.periodSeconds) {
        if (state.code == null) return@LaunchedEffect
        val period = state.periodSeconds * 1000L
        delay(period - System.currentTimeMillis() % period + 50)
        onCheck()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (state.replaces) R.string.totp_change else R.string.totp_setup)) },
        text = {
            AccessibleToToolsOnly() // a dialog is a window of its own
            HiddenFromOtherAutofill()
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.totp_setup_explained), style = MaterialTheme.typography.bodyMedium)
                OutlinedSecureTextField(
                    state = state.input,
                    modifier = Modifier
                        .hideTextFromAccessibility(state.input, shown = false)
                        .fillMaxWidth(),
                    label = { Text(stringResource(R.string.totp_setup_key)) },
                )
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                state.code?.let { code ->
                    Column {
                        Text(
                            grouped(code),
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            pluralStringResource(R.plurals.totp_setup_code, state.periodSeconds, state.periodSeconds),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (state.replaces) {
                    Text(stringResource(R.string.totp_setup_replaces), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd, enabled = state.code != null) { Text(stringResource(R.string.totp_setup_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "287082" as "287 082", easier to read off and type; other lengths as they are. */
private fun grouped(code: String): String =
    if (code.length == 6 || code.length == 8) code.chunked(code.length / 2).joinToString(" ") else code

/**
 * Hides the window this is called in from other apps' autofill services, as the activities do
 * for theirs. A dialog is a window of its own, and without this another password manager would
 * be shown the setup key field (a password field) and could offer to save what's pasted there.
 */
@Composable
private fun HiddenFromOtherAutofill() {
    val root = LocalView.current.rootView
    DisposableEffect(root) {
        root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose {}
    }
}
