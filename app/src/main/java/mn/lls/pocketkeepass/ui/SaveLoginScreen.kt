package mn.lls.pocketkeepass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.SaveProblem
import mn.lls.pocketkeepass.core.EntryItem
import mn.lls.pocketkeepass.core.GroupChoice
import mn.lls.pocketkeepass.core.ItemIcon
import mn.lls.pocketkeepass.core.LoginCandidate
import mn.lls.pocketkeepass.core.SearchResult
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

/**
 * Asking where to save a login typed into another app: a new entry (with a title and a group),
 * or an entry already linked to that app or site, whose password is replaced. Where it was typed
 * is always at the top, as on the autofill chooser. The password is never shown.
 *
 * [onSave] gets the chosen entry's id, or null for a new entry in the chosen group with the
 * given title.
 */
@Composable
fun SaveLoginScreen(
    destination: FillDestination,
    /** The website an app that isn't a verified browser says it shows, or null. */
    claimedDomain: String?,
    username: String,
    candidates: List<LoginCandidate>,
    initialEntry: String?,
    groups: List<GroupChoice>,
    initialGroup: String,
    initialTitle: String,
    databaseName: String,
    needsUpgrade: Boolean,
    onSave: (entryId: String?, groupId: String, title: String) -> Unit,
    onCancel: () -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf(initialEntry) }
    var group by rememberSaveable { mutableStateOf(initialGroup) }
    var pickingGroup by rememberSaveable { mutableStateOf(false) }
    val title = rememberTextFieldState(initialTitle)
    PasskeyFrame(
        label = stringResource(R.string.autofill_save_for, destination.kind),
        title = destination.name,
        subtitle = destination.label,
        onCancel = onCancel,
        buttons = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = { onSave(chosen, group, title.text.toString()) }) { Text(stringResource(R.string.save)) }
        },
    ) { padding ->
        if (pickingGroup) {
            GroupPickerDialog(
                title = stringResource(R.string.passkey_choose_group),
                groups = groups,
                databaseName = databaseName,
                onPick = {
                    group = it
                    pickingGroup = false
                },
                onDismiss = { pickingGroup = false },
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().selectableGroup(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "about") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Note(
                        if (username.isEmpty()) {
                            stringResource(R.string.autofill_save_no_username)
                        } else {
                            stringResource(R.string.autofill_save_username, username)
                        },
                    )
                    if (claimedDomain != null) Note(stringResource(R.string.autofill_save_unverified, claimedDomain))
                    if (needsUpgrade) Note(stringResource(R.string.upgrade_explained))
                    Text(stringResource(R.string.passkey_save_in), style = MaterialTheme.typography.titleSmall)
                }
            }
            item(key = "new") {
                Choice(selected = chosen == null, onSelect = { chosen = null }) {
                    Column {
                        Text(stringResource(R.string.passkey_new_entry))
                        val current = groups.firstOrNull { it.id == group }
                        if (current != null) {
                            Text(
                                stringResource(R.string.passkey_in_group, groupName(current, databaseName)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (chosen == null) {
                    Column(Modifier.padding(start = 56.dp, end = 16.dp)) {
                        OutlinedTextField(
                            state = title,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.field_title)) },
                            lineLimits = TextFieldLineLimits.SingleLine,
                        )
                        if (groups.size > 1) {
                            TextButton(onClick = { pickingGroup = true }) { Text(stringResource(R.string.passkey_change_group)) }
                        }
                    }
                }
            }
            items(candidates, key = { it.result.entry.id }) { candidate ->
                val entry = candidate.result.entry
                Choice(selected = chosen == entry.id, onSelect = { chosen = entry.id }) {
                    Column {
                        Text(entry.title.ifEmpty { stringResource(R.string.untitled_entry) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val details = listOf(entry.username, candidate.result.groupPath.joinToString(PATH_SEPARATOR)).filter { it.isNotEmpty() }
                        if (details.isNotEmpty()) {
                            Text(
                                details.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            stringResource(
                                // Without a typed username, the entry keeps its own.
                                if (candidate.sameUsername || username.isEmpty()) R.string.autofill_save_update_password else R.string.autofill_save_replace,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** Waiting for the file to be written. */
@Composable
fun SaveLoginSavingScreen() {
    BackHandler {} // the save can't be stopped halfway
    PasskeyMessage(stringResource(R.string.autofill_saving), progress = true)
}

/** The login is in the database, but the file couldn't be written. */
@Composable
fun SaveLoginSaveFailedScreen(problem: SaveProblem, onRetry: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val reason = when (problem) {
        SaveProblem.Conflict -> stringResource(R.string.passkey_save_conflict)
        SaveProblem.CantWrite, SaveProblem.WriteCheckFailed -> stringResource(R.string.not_saved)
        is SaveProblem.Engine -> problem.error.userMessage()
    }
    PasskeyMessage(stringResource(R.string.autofill_not_saved, reason)) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
        if (problem != SaveProblem.Conflict) Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
    }
}

/** Why the login can't be saved. */
@Composable
fun SaveLoginFailedScreen(message: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    PasskeyMessage(message) {
        Button(onClick = onClose) { Text(stringResource(R.string.close)) }
    }
}

@Preview(showBackground = true)
@Composable
private fun SaveLoginScreenPreview() {
    PocketKeePassTheme {
        SaveLoginScreen(
            destination = FillDestination("Website", "login.example.com", label = null, isWebsite = true),
            claimedDomain = null,
            username = "me@example.com",
            candidates = listOf(
                LoginCandidate(SearchResult(EntryItem("01", "Example", "me@example.com", "https://example.com", ItemIcon(1u, null)), listOf("Sites")), true, false),
            ),
            initialEntry = "01",
            groups = listOf(GroupChoice("01", "Root", 0u, false, false, ItemIcon(48u, null))),
            initialGroup = "01",
            initialTitle = "example.com",
            databaseName = "Passwords",
            needsUpgrade = false,
            onSave = { _, _, _ -> },
            onCancel = {},
        )
    }
}
