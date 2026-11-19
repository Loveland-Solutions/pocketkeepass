package mn.lls.pocketkeepass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.SaveProblem
import mn.lls.pocketkeepass.core.EntryItem
import mn.lls.pocketkeepass.core.GroupChoice
import mn.lls.pocketkeepass.core.PasskeyCreation
import mn.lls.pocketkeepass.core.SearchResult
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

/**
 * Asking before a passkey is made: for which site and account, who is asking, and which entry
 * it goes into. The site is always at the top, since that's what the user has to check.
 *
 * [onCreate] gets the chosen entry's id, or null for a new entry in the chosen group.
 */
@Composable
fun PasskeyCreateScreen(
    creation: PasskeyCreation,
    /** Where a new entry can go, in tree order. */
    groups: List<GroupChoice>,
    initialGroup: String,
    databaseName: String,
    /** The website, or the app's name and package. */
    caller: String,
    callerIsApp: Boolean,
    needsUpgrade: Boolean,
    onCreate: (entryId: String?, groupId: String) -> Unit,
    onCancel: () -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableStateOf(initialGroup) }
    var pickingGroup by rememberSaveable { mutableStateOf(false) }
    val site = creation.rpName.ifEmpty { creation.rpId }
    PasskeyFrame(
        label = stringResource(R.string.passkey_create_for),
        title = site,
        subtitle = creation.rpId.takeIf { it != site },
        onCancel = onCancel,
        buttons = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = { onCreate(chosen, group) }) { Text(stringResource(R.string.passkey_create)) }
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).selectableGroup(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "about") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val account = listOf(creation.userName, creation.userDisplayName).filter { it.isNotEmpty() }.distinct()
                    if (account.isNotEmpty()) Note(stringResource(R.string.passkey_account, account.joinToString(" · ")))
                    Note(
                        if (callerIsApp) {
                            stringResource(R.string.passkey_create_app, caller, creation.rpId)
                        } else {
                            stringResource(R.string.passkey_create_browser, caller)
                        },
                    )
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
                if (chosen == null && groups.size > 1) {
                    TextButton(onClick = { pickingGroup = true }, modifier = Modifier.padding(start = 56.dp)) {
                        Text(stringResource(R.string.passkey_change_group))
                    }
                }
            }
            items(creation.candidates, key = { it.entry.id }) { result ->
                Choice(selected = chosen == result.entry.id, onSelect = { chosen = result.entry.id }) {
                    Column {
                        Text(result.entry.title.ifEmpty { stringResource(R.string.untitled_entry) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val details = listOf(result.entry.username, result.groupPath.joinToString(PATH_SEPARATOR)).filter { it.isNotEmpty() }
                        if (details.isNotEmpty()) {
                            Text(
                                details.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Asking whether an app may use a passkey it isn't linked to yet. PocketKeePass can't check
 * (that needs the internet), so the user decides, once.
 */
@Composable
fun PasskeyLinkScreen(app: String, site: String, entryTitle: String, needsUpgrade: Boolean, onAllow: () -> Unit, onCancel: () -> Unit) {
    PasskeyFrame(
        label = stringResource(R.string.passkey_link_label),
        title = app,
        subtitle = null,
        onCancel = onCancel,
        buttons = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onAllow) { Text(stringResource(R.string.passkey_allow)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Note(stringResource(R.string.passkey_link_explained, app, site, entryTitle))
            if (needsUpgrade) Note(stringResource(R.string.upgrade_explained))
        }
    }
}

/** Waiting for a new passkey to be saved. */
@Composable
fun PasskeySavingScreen() {
    BackHandler {} // the save can't be stopped halfway
    PasskeyMessage(stringResource(R.string.passkey_saving), progress = true)
}

/** A new passkey couldn't be saved. Cancelling takes it back out of the database. */
@Composable
fun PasskeySaveFailedScreen(problem: SaveProblem, onRetry: () -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val reason = when (problem) {
        SaveProblem.Conflict -> stringResource(R.string.passkey_save_conflict)
        SaveProblem.CantWrite, SaveProblem.WriteCheckFailed -> stringResource(R.string.not_saved)
        is SaveProblem.Engine -> problem.error.userMessage()
    }
    PasskeyMessage(stringResource(R.string.passkey_not_saved, reason)) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        if (problem != SaveProblem.Conflict) Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
    }
}

/** Why a passkey can't be made or used. */
@Composable
fun PasskeyFailedScreen(message: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    PasskeyMessage(message) {
        Button(onClick = onClose) { Text(stringResource(R.string.close)) }
    }
}

@Composable
private fun PasskeyFrame(
    label: String,
    title: String,
    subtitle: String?,
    onCancel: () -> Unit,
    buttons: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onCancel)
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCancel) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel))
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            subtitle?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) { buttons() }
            }
        },
        content = content,
    )
}

/** A short message over the other app, with buttons. */
@Composable
private fun PasskeyMessage(message: String, progress: Boolean = false, buttons: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (progress) CircularProgressIndicator()
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { buttons() }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Choice(selected: Boolean, onSelect: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Box(Modifier.padding(start = 16.dp)) { content() }
    }
}

@Preview(showBackground = true)
@Composable
private fun PasskeyCreateScreenPreview() {
    PocketKeePassTheme {
        PasskeyCreateScreen(
            creation = PasskeyCreation(
                rpId = "example.com",
                rpName = "Example",
                userName = "me@example.com",
                userDisplayName = "Me",
                candidates = listOf(SearchResult(EntryItem("01", "Example", "me@example.com", "https://example.com"), listOf("Sites"))),
            ),
            groups = listOf(GroupChoice("01", "Root", 0u, false, false), GroupChoice("02", "Sites", 1u, false, false)),
            initialGroup = "02",
            databaseName = "Passwords",
            caller = "example.com",
            callerIsApp = false,
            needsUpgrade = false,
            onCreate = { _, _ -> },
            onCancel = {},
        )
    }
}
