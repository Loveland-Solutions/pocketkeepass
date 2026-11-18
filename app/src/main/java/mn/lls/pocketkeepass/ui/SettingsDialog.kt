package mn.lls.pocketkeepass.ui

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.credentials.CredentialManager
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.passkey.PocketPasskeyService
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import mn.lls.pocketkeepass.security.AutoLockSettings
import mn.lls.pocketkeepass.security.Clipboard
import mn.lls.pocketkeepass.security.QuickUnlockSetting

/**
 * Chooses when the open database locks itself, when the clipboard is cleared, whether
 * biometrics can unlock the database, and whether PocketKeePass fills in other apps' logins and provides passkeys. Changes apply straight away. Ends with the credits.
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
                AutofillSetting()
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PasskeySetting()
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
                PrivacyPolicyLink()
            }
        },
    )
}

/**
 * Asked once per database, right after it's first unlocked with the master password: whether to
 * unlock it with biometrics from now on. "Turn on" goes on to Android's biometric prompt, as the
 * switch in settings does.
 */
@Composable
fun QuickUnlockOfferDialog(databaseName: String, onTurnOn: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.biometric_offer_title)) },
        text = {
            AccessibleToToolsOnly() // a dialog is a window of its own
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.biometric_offer_explained, databaseName))
                Text(
                    stringResource(R.string.biometric_unlock_explained),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTurnOn) { Text(stringResource(R.string.biometric_offer_turn_on)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) } },
    )
}

/**
 * Opens the privacy policy in the browser. PocketKeePass itself has no internet access, so the
 * browser fetches the page. If the phone has no browser, the button does nothing.
 */
@Composable
private fun PrivacyPolicyLink() {
    val context = LocalContext.current
    TextButton(onClick = {
        val intent = Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser installed. The address is also on the store listing.
        }
    }) {
        Text(stringResource(R.string.privacy_policy))
    }
}

/** Also linked from Google Play. The text is PRIVACY.md in the repository. */
private const val PRIVACY_POLICY_URL = "https://lls.mn/en/pocketkeepass/privacy/"

/**
 * Whether PocketKeePass is the phone's autofill service. Android owns that choice, so turning it
 * on opens Android's own screen for it. The switch re-reads the answer whenever the app comes
 * back to the front.
 */
@Composable
private fun AutofillSetting() {
    val context = LocalContext.current
    val autofill = remember(context) { context.getSystemService(AutofillManager::class.java) }
    val supported = autofill?.isAutofillSupported == true
    var on by remember { mutableStateOf(false) }
    LifecycleResumeEffect(autofill) {
        on = autofill?.hasEnabledAutofillServices() == true
        onPauseOrDispose {}
    }
    Text(stringResource(R.string.autofill), style = MaterialTheme.typography.titleSmall)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(on, enabled = supported, role = Role.Switch) { turnOn ->
                if (turnOn) {
                    val choose = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, "package:${context.packageName}".toUri())
                    try {
                        context.startActivity(choose)
                    } catch (_: ActivityNotFoundException) {
                        // A phone without that screen. The switch stays off.
                    }
                } else {
                    autofill?.disableAutofillServices()
                    on = false
                }
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.autofill_switch), Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null, enabled = supported)
    }
    Text(
        stringResource(if (supported) R.string.autofill_explained else R.string.autofill_unsupported),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Whether PocketKeePass provides passkeys (Android 14+). Like autofill, Android owns that choice:
 * the switch opens Android's own screen for it, and re-reads the answer when the app comes back.
 */
@Composable
private fun PasskeySetting() {
    val context = LocalContext.current
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    var on by remember { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        on = supported && passkeysEnabled(context)
        onPauseOrDispose {}
    }
    Text(stringResource(R.string.passkeys), style = MaterialTheme.typography.titleSmall)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(on, enabled = supported, role = Role.Switch) {
                // On or off, it's changed in Android's settings.
                if (supported) openPasskeySettings(context)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.passkeys_switch), Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null, enabled = supported)
    }
    Text(
        stringResource(if (supported) R.string.passkeys_explained else R.string.passkeys_unsupported),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun openPasskeySettings(context: Context) {
    try {
        androidx.credentials.CredentialManager.create(context).createSettingsPendingIntent().send()
    } catch (_: PendingIntent.CanceledException) {
        // A phone without that screen. Nothing changes.
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun passkeysEnabled(context: Context): Boolean {
    val manager = context.getSystemService(CredentialManager::class.java) ?: return false
    return manager.isEnabledCredentialProviderService(ComponentName(context, PocketPasskeyService::class.java))
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
