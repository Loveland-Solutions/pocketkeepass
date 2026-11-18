package mn.lls.passthekey.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mn.lls.passthekey.R
import mn.lls.passthekey.core.EntryDetails
import mn.lls.passthekey.core.EntryField
import mn.lls.passthekey.ui.theme.PassTheKeyTheme

/** What a hidden value looks like. Always the same length, so it gives nothing away. */
private const val HIDDEN = "••••••••"

/**
 * One entry: its fields, with protected ones hidden until the user asks to see them, and a copy
 * button for each.
 *
 * Revealed values are kept only while this screen is showing this entry: they're forgotten when
 * the user goes back, opens another entry, or the database locks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
    entry: EntryDetails,
    databaseName: String,
    clipboardTimeoutSeconds: Int,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onReveal: suspend (EntryField) -> String?,
    onCopy: (field: EntryField, label: String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val revealed = remember(entry.id) { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val title = entry.title.ifEmpty { stringResource(R.string.untitled_entry) }

    // Android 13+ confirms a copy itself. Older versions don't, so say it here.
    val confirmCopies = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    val clearsIn = clipboardDuration(clipboardTimeoutSeconds)
    val copiedMessage = stringResource(R.string.copied)
    val copy: (EntryField, String) -> Unit = { field, label ->
        onCopy(field, label)
        if (confirmCopies) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(copiedMessage.format(label, clearsIn))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (listOf(databaseName) + entry.groupPath).joinToString(" › "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onLock) {
                        Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.lock))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val fields = entry.fields.filter { !it.isEmpty }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            if (entry.isInRecycleBin) {
                Text(
                    stringResource(R.string.entry_in_recycle_bin),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (fields.isEmpty() && entry.tags.isEmpty()) {
                Box(Modifier.padding(32.dp)) {
                    Text(
                        stringResource(R.string.entry_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for (field in fields) {
                val label = fieldLabel(field.key)
                FieldRow(
                    field = field,
                    label = label,
                    shown = revealed[field.key],
                    onToggle = {
                        if (revealed.remove(field.key) == null) {
                            scope.launch { onReveal(field)?.let { revealed[field.key] = it } }
                        }
                    },
                    onCopy = { copy(field, label) },
                )
            }
            if (entry.tags.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(
                    overlineContent = { Text(stringResource(R.string.field_tags)) },
                    headlineContent = { Text(entry.tags.joinToString(", ")) },
                )
            }
        }
    }
}

/**
 * A field: its name, its value (or dots, if it's protected and not shown), and buttons.
 * [shown] is the revealed value of a protected field, or null while it's hidden.
 */
@Composable
private fun FieldRow(field: EntryField, label: String, shown: String?, onToggle: () -> Unit, onCopy: () -> Unit) {
    val isNotes = field.key == "Notes"
    val value = when {
        !field.isProtected -> field.value
        shown != null -> shown
        else -> HIDDEN
    }
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = {
            Text(
                value,
                // Shown passwords in monospace, so that l, I, 1, O, and 0 can be told apart.
                fontFamily = if (field.isProtected && shown != null && field.key == "Password") FontFamily.Monospace else null,
                maxLines = if (isNotes) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row {
                if (field.isProtected) {
                    IconButton(onClick = onToggle) {
                        Icon(
                            painterResource(if (shown != null) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            stringResource(if (shown != null) R.string.hide_field else R.string.show_field, label),
                        )
                    }
                }
                IconButton(onClick = onCopy) {
                    Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.copy_field, label))
                }
            }
        },
    )
}

/** The name shown for a field: standard fields get translated names, custom ones their own. */
@Composable
private fun fieldLabel(key: String): String = when (key) {
    "UserName" -> stringResource(R.string.field_username)
    "Password" -> stringResource(R.string.field_password)
    "URL" -> stringResource(R.string.field_url)
    "Notes" -> stringResource(R.string.field_notes)
    else -> key
}

@Composable
private fun clipboardDuration(seconds: Int): String =
    if (seconds % 60 == 0) {
        pluralStringResource(R.plurals.minutes, seconds / 60, seconds / 60)
    } else {
        pluralStringResource(R.plurals.seconds, seconds, seconds)
    }

@Preview(showBackground = true)
@Composable
private fun EntryScreenPreview() {
    PassTheKeyTheme {
        EntryScreen(
            EntryDetails(
                id = "02",
                title = "Email",
                groupPath = listOf("Personal"),
                isInRecycleBin = false,
                fields = listOf(
                    EntryField("UserName", "me@example.com", isProtected = false, isEmpty = false),
                    EntryField("Password", "", isProtected = true, isEmpty = false),
                    EntryField("URL", "https://mail.example.com", isProtected = false, isEmpty = false),
                    EntryField("Notes", "Personal inbox", isProtected = false, isEmpty = false),
                ),
                tags = listOf("personal"),
            ),
            databaseName = "Passwords",
            clipboardTimeoutSeconds = 30,
            onBack = {},
            onLock = {},
            onReveal = { null },
            onCopy = { _, _ -> },
        )
    }
}
