package mn.lls.pocketkeepass.ui

import android.os.Build
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.delay
import mn.lls.pocketkeepass.core.TotpStatus
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.SaveState
import mn.lls.pocketkeepass.core.EntryDetails
import mn.lls.pocketkeepass.core.EntryField
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

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
    onTotpCode: suspend (unixSeconds: Long) -> String?,
    onCopyTotp: (label: String) -> Unit,
    saveState: SaveState,
    /** A save just finished: say so for a moment. */
    justSaved: Boolean,
    onSaveDetails: () -> Unit,
    snackbar: SnackbarHostState,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Forgotten when the entry changes too: an edit may have changed what's behind the dots.
    val revealed = remember(entry) { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    val title = entry.title.ifEmpty { stringResource(R.string.untitled_entry) }

    // Android 13+ confirms a copy itself. Older versions don't, so say it here.
    val confirmCopies = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    val clearsIn = clipboardDuration(clipboardTimeoutSeconds)
    val copiedMessage = stringResource(R.string.copied)
    val confirm: (String) -> Unit = { label ->
        if (confirmCopies) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(copiedMessage.format(label, clearsIn))
            }
        }
    }
    val copy: (EntryField, String) -> Unit = { field, label ->
        onCopy(field, label)
        confirm(label)
    }
    val copyText: (String) -> Unit = { label ->
        onCopyTotp(label)
        confirm(label)
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
                    IconButton(onClick = onEdit) {
                        Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit_entry))
                    }
                    IconButton(onClick = onLock) {
                        Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.lock))
                    }
                    EntryMenu(onDuplicate.takeUnless { entry.isInRecycleBin }, onMove, onDelete)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val totp = entry.totp
        // Fields holding TOTP settings are left out when the code can be shown instead. Unusable
        // ones stay, so the user can see what's there.
        val totpFields = (totp as? TotpStatus.Ready)?.fields.orEmpty()
        // A passkey's fields (its key, IDs, app certificates) are shown as one row instead.
        val passkeyFields = entry.passkey?.fields.orEmpty()
        val fields = entry.fields.filter { !it.isEmpty && it.key !in totpFields && it.key !in passkeyFields }
        // The code goes right after the password, or first if there's none.
        val totpAfter = fields.indexOfFirst { it.key == "Password" }
        val totpLabel = stringResource(R.string.totp)
        val totpRow = @Composable {
            when (totp) {
                is TotpStatus.Ready -> key(entry) {
                    TotpRow(totp, totpLabel, onTotpCode, onCopy = { copyText(totpLabel) })
                }
                is TotpStatus.Invalid -> ListItem(
                    overlineContent = { Text(totpLabel) },
                    headlineContent = {
                        // The engine's own sentence, e.g. "The TOTP secret isn't valid Base32."
                        Text(totp.reason, color = MaterialTheme.colorScheme.error)
                    },
                )
                TotpStatus.None -> {}
            }
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
        SaveStatusBar(saveState, justSaved, onSaveDetails)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            if (entry.isInRecycleBin) {
                Text(
                    stringResource(R.string.entry_in_recycle_bin),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (fields.isEmpty() && entry.tags.isEmpty() && totp is TotpStatus.None && entry.passkey == null) {
                Box(Modifier.padding(32.dp)) {
                    Text(
                        stringResource(R.string.entry_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (totpAfter < 0) totpRow()
            for ((index, field) in fields.withIndex()) {
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
                if (index == totpAfter) totpRow()
            }
            entry.passkey?.let { passkey ->
                ListItem(
                    overlineContent = { Text(stringResource(R.string.passkey)) },
                    headlineContent = { Text(stringResource(R.string.passkey_for, passkey.rpId)) },
                    supportingContent = {
                        // The engine's own sentence when it can't be used, e.g. another key type.
                        val reason = passkey.unusableReason
                        if (reason != null) {
                            Text(reason, color = MaterialTheme.colorScheme.error)
                        } else if (passkey.username.isNotEmpty()) {
                            Text(passkey.username)
                        }
                    },
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
}

/** Copy, move, or delete the entry. Without [onDuplicate] (in the recycle bin), no copying. */
@Composable
private fun EntryMenu(onDuplicate: (() -> Unit)?, onMove: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onDuplicate != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.duplicate)) },
                    onClick = {
                        open = false
                        onDuplicate()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move)) },
                onClick = {
                    open = false
                    onMove()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
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

/**
 * The entry's current TOTP code, hidden until the user asks to see it (like a password: on
 * Android 8 to 13, any accessibility app the user turned on could read it, and it's the second
 * factor). Shown, it's fetched again whenever a new period starts. The countdown always shows,
 * so the user can tell whether there's time to type the code before it changes.
 *
 * Call it inside `key(entry)`, so a shown code is forgotten when the entry changes.
 */
@Composable
private fun TotpRow(
    totp: TotpStatus.Ready,
    label: String,
    onCode: suspend (unixSeconds: Long) -> String?,
    onCopy: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            val millis = System.currentTimeMillis()
            now = millis / 1000
            delay(1000 - millis % 1000) // tick on the second
        }
    }
    val period = totp.periodSeconds.toLong()
    val periodStart = now - now % period
    val remaining = (period - (now - periodStart)).toInt()
    LaunchedEffect(shown, periodStart) {
        code = if (shown) onCode(periodStart) else null
    }
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = {
            val shownCode = code
            Text(
                if (shown && shownCode != null) groupTotp(shownCode) else HIDDEN,
                fontFamily = if (shown && shownCode != null) FontFamily.Monospace else null,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        supportingContent = {
            Text(pluralStringResource(R.plurals.totp_changes_in, remaining, remaining))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    progress = { remaining.toFloat() / period },
                    modifier = Modifier.size(20.dp).clearAndSetSemantics {},
                    strokeWidth = 3.dp,
                )
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        stringResource(if (shown) R.string.hide_field else R.string.show_field, label),
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.copy_field, label))
                }
            }
        },
    )
}

/** "123 456" for a 6-digit code, "1234 5678" for 8: halves are easier to read and type. */
private fun groupTotp(code: String): String =
    if (code.length >= 6) code.substring(0, code.length / 2) + " " + code.substring(code.length / 2) else code

/** The name shown for a field: standard fields get translated names, custom ones their own. */
@Composable
internal fun fieldLabel(key: String): String = when (key) {
    "Title" -> stringResource(R.string.field_title)
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
    PocketKeePassTheme {
        EntryScreen(
            EntryDetails(
                id = "02",
                title = "Email",
                titleIsProtected = false,
                groupPath = listOf("Personal"),
                isInRecycleBin = false,
                fields = listOf(
                    EntryField("UserName", "me@example.com", isProtected = false, isEmpty = false),
                    EntryField("Password", "", isProtected = true, isEmpty = false),
                    EntryField("URL", "https://mail.example.com", isProtected = false, isEmpty = false),
                    EntryField("Notes", "Personal inbox", isProtected = false, isEmpty = false),
                ),
                tags = listOf("personal"),
                totp = TotpStatus.Ready(periodSeconds = 30u, length = 6u, fields = listOf("otp")),
                passkey = null,
            ),
            databaseName = "Passwords",
            clipboardTimeoutSeconds = 30,
            onBack = {},
            onLock = {},
            onReveal = { null },
            onCopy = { _, _ -> },
            onTotpCode = { "123456" },
            onCopyTotp = {},
            saveState = SaveState.Saved,
            justSaved = false,
            onSaveDetails = {},
            snackbar = SnackbarHostState(),
            onEdit = {},
            onDuplicate = {},
            onMove = {},
            onDelete = {},
        )
    }
}
