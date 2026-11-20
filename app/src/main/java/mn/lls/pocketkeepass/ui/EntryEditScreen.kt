package mn.lls.pocketkeepass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.edit.EntryForm
import mn.lls.pocketkeepass.edit.FormField
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import mn.lls.pocketkeepass.security.hideTextFromAccessibility

/**
 * Creating or editing an entry. Saving hands the fields to the engine, which keeps the old
 * version in the entry's history, and the database is saved to its file straight after.
 *
 * Protected values the entry already has are shown as dots and aren't fetched until the user
 * taps "Change": until then, saving keeps them exactly as they are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditScreen(
    form: EntryForm,
    busy: Boolean,
    error: String?,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onBeginEditing: (FormField) -> Unit,
    onGenerate: (FormField) -> Unit,
    onCopy: (FormField, label: String) -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val close = { if (form.isModified()) confirmDiscard = true else onClose() }
    BackHandler(onBack = close)
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_changes)) },
            text = { AccessibleToToolsOnly() },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_editing)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
                    }
                },
                title = { Text(stringResource(if (form.isNew) R.string.new_entry else R.string.edit_entry)) },
                actions = {
                    TextButton(onClick = onSave, enabled = !busy) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            for (field in form.fields) {
                key(field) {
                    FieldEditor(
                        field,
                        onBeginEditing = { onBeginEditing(field) },
                        onGenerate = { onGenerate(field) },
                        onCopy = { label -> onCopy(field, label) },
                        onRemove = { form.removeField(field) },
                    )
                }
            }
            TextButton(onClick = { form.addField() }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_field))
            }
            OutlinedTextField(
                state = form.tags,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_tags)) },
                supportingText = { Text(stringResource(R.string.tags_hint)) },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        }
    }
}

/**
 * One field: its value, and for custom fields, its name, a "protect" switch, and "remove".
 * The password, and any other protected field, can be filled in by the generator.
 */
@Composable
private fun FieldEditor(
    field: FormField,
    onBeginEditing: () -> Unit,
    onGenerate: () -> Unit,
    onCopy: (label: String) -> Unit,
    onRemove: () -> Unit,
) {
    val isCustom = field.standardKey == null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (isCustom) {
            HorizontalDivider(Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                state = field.name,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_name)) },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        }
        val label = if (isCustom) stringResource(R.string.field_value) else fieldLabel(field.key)
        val value = field.value
        when {
            value == null -> UnfetchedField(label, onBeginEditing)
            field.protected -> SecretField(value, label, onCopy = { onCopy(label) })
            else -> OutlinedTextField(
                state = value,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                lineLimits = if (field.key == EntryForm.NOTES || isCustom) {
                    TextFieldLineLimits.MultiLine(minHeightInLines = if (field.key == EntryForm.NOTES) 3 else 1)
                } else {
                    TextFieldLineLimits.SingleLine
                },
            )
        }
        if (field.protected || field.standardKey == EntryForm.PASSWORD) {
            TextButton(onClick = onGenerate) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.generate_password))
            }
        }
        if (isCustom) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = field.protected, onCheckedChange = { field.protected = it })
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.protect_field), Modifier.weight(1f))
                TextButton(onClick = onRemove) {
                    Icon(painterResource(R.drawable.ic_remove_circle), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remove_field))
                }
            }
        }
    }
}

/**
 * A protected value that hasn't been fetched: fixed dots (they give nothing away, not even the
 * length) and a button to change it.
 */
@Composable
private fun UnfetchedField(label: String, onBeginEditing: () -> Unit) {
    val dots = remember { TextFieldState(HIDDEN_VALUE) }
    OutlinedTextField(
        state = dots,
        modifier = Modifier.fillMaxWidth(),
        readOnly = true,
        label = { Text(label) },
        lineLimits = TextFieldLineLimits.SingleLine,
        trailingIcon = {
            IconButton(onClick = onBeginEditing) {
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.change_field, label))
            }
        },
    )
}

/**
 * A protected value being edited: masked unless the user chooses to see it. It has its own copy
 * button, because a secure text field doesn't let its text be selected and copied.
 */
@Composable
private fun SecretField(state: TextFieldState, label: String, onCopy: () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = state,
        // First in the chain, so accessibility apps get dots instead of the text.
        modifier = Modifier.hideTextFromAccessibility(state, shown).fillMaxWidth(),
        label = { Text(label) },
        textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        // Shown passwords in monospace, so that l, I, 1, O, and 0 can be told apart.
        textStyle = if (shown) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
        trailingIcon = {
            Row {
                IconButton(onClick = onCopy, enabled = state.text.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.copy_field, label))
                }
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        stringResource(if (shown) R.string.hide_field else R.string.show_field, label),
                    )
                }
            }
        },
    )
}

/** What an unfetched protected value looks like. Always the same, so it gives nothing away. */
private const val HIDDEN_VALUE = "••••••••"
