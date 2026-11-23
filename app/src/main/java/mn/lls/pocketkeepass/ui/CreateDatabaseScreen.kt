package mn.lls.pocketkeepass.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import mn.lls.pocketkeepass.NewDatabaseState
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.security.hideTextFromAccessibility

/**
 * Making a new database: a name and the master password twice, then Android's "create
 * document" screen for where the file goes ([onChooseLocation], called by itself once the
 * database is made, and again from the button if the user backed out of it).
 *
 * A screen of the activity's own rather than a dialog: dialogs are windows of their own, and
 * only the activity's window is hidden from other autofill services, which could otherwise
 * offer to save the master password.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateDatabaseScreen(
    state: NewDatabaseState,
    name: TextFieldState,
    password: TextFieldState,
    passwordAgain: TextFieldState,
    onCreate: () -> Unit,
    onChooseLocation: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(enabled = !state.busy, onBack = onCancel)
    var showPassword by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Read the two fields as they change, for the "different" warning and the Create button.
    var differ by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(name, password, passwordAgain) {
        snapshotFlow { Triple(name.text.toString(), password.text.toString(), passwordAgain.text.toString()) }
            .collect { (n, p, a) ->
                differ = a.isNotEmpty() && p != a
                ready = n.isNotBlank() && p.isNotEmpty() && p == a
            }
    }
    // Once made, straight on to choosing where it goes, once: not again after a rotation.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.made) {
        if (state.made && !asked) {
            asked = true
            onChooseLocation()
        }
    }
    val editable = !state.busy && !state.made

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_database_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !state.busy) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                state = name,
                enabled = editable,
                label = { Text(stringResource(R.string.new_database_name)) },
                supportingText = { Text(stringResource(R.string.new_database_name_hint)) },
                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            secureField(password, R.string.master_password, showPassword, editable, Modifier.focusRequester(focus), ImeAction.Next)
            secureField(passwordAgain, R.string.new_database_password_again, showPassword, editable, Modifier, ImeAction.Done) {
                if (ready) onCreate()
            }
            Row {
                androidx.compose.material3.TextButton(onClick = { showPassword = !showPassword }) {
                    Text(stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                }
            }
            if (differ) {
                Text(stringResource(R.string.new_database_passwords_differ), color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(R.string.new_database_password_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.made) {
                Button(onClick = onChooseLocation, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.new_database_choose_location))
                }
            } else {
                Button(onClick = onCreate, enabled = ready && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.create))
                    }
                }
            }
            if (state.busy && !state.made) {
                Text(
                    stringResource(R.string.new_database_creating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.new_database_sync_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A master password field: dots for accessibility apps, no "new password" hint for autofill. */
@Composable
private fun secureField(
    state: TextFieldState,
    label: Int,
    shown: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    imeAction: ImeAction,
    onDone: () -> Unit = {},
) {
    OutlinedSecureTextField(
        state = state,
        enabled = enabled,
        label = { Text(stringResource(label)) },
        textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        onKeyboardAction = { onDone() },
        modifier = modifier
            .hideTextFromAccessibility(state, shown = shown)
            .fillMaxWidth(),
    )
}
