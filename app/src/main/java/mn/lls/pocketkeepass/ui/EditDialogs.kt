package mn.lls.pocketkeepass.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.core.GroupChoice
import mn.lls.pocketkeepass.SaveProblem
import mn.lls.pocketkeepass.SaveState
import mn.lls.pocketkeepass.edit.GroupForm
import mn.lls.pocketkeepass.edit.Item
import mn.lls.pocketkeepass.edit.MoveChoice
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import kotlinx.coroutines.delay

/** Creating a group, or renaming one and changing its notes. */
@Composable
fun GroupDialog(form: GroupForm, busy: Boolean, error: String?, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (form.isNew) R.string.new_group else R.string.edit_group)) },
        text = {
            AccessibleToToolsOnly() // a dialog is a window of its own
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    state = form.name,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.group_name)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
                OutlinedTextField(
                    state = form.notes,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.field_notes)) },
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !busy && form.name.text.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Asks before deleting. Says whether it goes to the recycle bin or is gone for good, which is
 * the case inside the recycle bin, for the bin itself, and when the bin is turned off.
 */
@Composable
fun DeleteDialog(item: Item, recycleBinEnabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val forGood = !recycleBinEnabled || item.inRecycleBin || (item is Item.Group && item.isRecycleBin)
    val name = item.name.ifEmpty {
        stringResource(if (item is Item.Entry) R.string.untitled_entry else R.string.untitled_group)
    }
    val message = when {
        item is Item.Group && item.isRecycleBin -> R.string.delete_recycle_bin_explained
        forGood && item is Item.Group -> R.string.delete_group_for_good_explained
        forGood -> R.string.delete_entry_for_good_explained
        item is Item.Group -> R.string.recycle_group_explained
        else -> R.string.recycle_entry_explained
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (forGood) R.string.delete_for_good_title else R.string.recycle_title)) },
        text = {
            AccessibleToToolsOnly()
            Text(stringResource(message, name))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (forGood) R.string.delete else R.string.move_to_recycle_bin))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Picks the group to move an entry or group into, from a tree of every group it may go to. */
@Composable
fun MoveDialog(choice: MoveChoice, databaseName: String, onPick: (groupId: String) -> Unit, onDismiss: () -> Unit) {
    val name = choice.item.name.ifEmpty {
        stringResource(if (choice.item is Item.Entry) R.string.untitled_entry else R.string.untitled_group)
    }
    GroupPickerDialog(stringResource(R.string.move_to, name), choice.groups, databaseName, onPick, onDismiss)
}

/**
 * Picks a group from [groups], shown as a tree (they must be in tree order, as `all_groups`
 * lists them). The root group is shown with the database's name.
 */
@Composable
fun GroupPickerDialog(
    title: String,
    groups: List<GroupChoice>,
    databaseName: String,
    onPick: (groupId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            AccessibleToToolsOnly()
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(groups, key = { it.id }) { group ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(group.id) }
                            .padding(start = (group.depth.toInt() * 20).dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GroupIcon(group.icon, group.isRecycleBin)
                        Text(groupName(group, databaseName), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A group's name as pickers show it: the database's name for the root group. */
@Composable
fun groupName(group: GroupChoice, databaseName: String): String =
    if (group.depth == 0u) databaseName else group.name.ifEmpty { stringResource(R.string.untitled_group) }

/** Asks to convert a KDBX 3 database to KDBX 4, which is the only format PocketKeePass saves. */
@Composable
fun UpgradeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upgrade_title)) },
        text = {
            AccessibleToToolsOnly()
            Text(stringResource(R.string.upgrade_explained))
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.upgrade)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** How long "Saved" stays under the top bar after a save. */
private const val SAVED_NOTICE_MS = 2_000L

/**
 * True for a moment after a save finishes, so the screen can say so. Keep it in the app's
 * top-level composable rather than in each screen, so a save that ends while the screen changes
 * (closing the entry editor, for one) is still noticed.
 */
@Composable
fun rememberJustSaved(state: SaveState): Boolean {
    var justSaved by remember { mutableStateOf(false) }
    var wasSaving by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            SaveState.Saving -> {
                wasSaving = true
                justSaved = false
            }
            SaveState.Saved -> if (wasSaving) {
                wasSaving = false
                justSaved = true
                delay(SAVED_NOTICE_MS)
                justSaved = false
            }
            SaveState.Waiting, is SaveState.Unsaved -> {
                wasSaving = false
                justSaved = false
            }
        }
    }
    return justSaved
}

/**
 * A thin bar under the top bar while saving, "Saved" for a moment afterwards ([justSaved], from
 * [rememberJustSaved]), a note while changes wait on this phone for a connection, or a warning
 * while changes aren't saved, with a button for what can be done about it. Nothing otherwise.
 */
@Composable
fun SaveStatusBar(state: SaveState, justSaved: Boolean, onDetails: () -> Unit) {
    when (state) {
        SaveState.Saved -> AnimatedVisibility(
            visible = justSaved,
            enter = fadeIn(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            // The same height as the "Saving…" bar it replaces, so nothing below jumps.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.saved),
                    Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        SaveState.Saving -> Column {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.saving),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SaveState.Waiting -> Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                stringResource(R.string.waiting_for_connection),
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        is SaveState.Unsaved -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.not_saved),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = onDetails) { Text(stringResource(R.string.what_now)) }
            }
        }
    }
}

/**
 * Why changes weren't saved, and the ways out. After a conflict: replace the other version,
 * throw ours away and reload, or save ours as a separate copy. Otherwise: try again, or save a
 * copy. Changes stay in memory meanwhile, until the database locks.
 */
@Composable
fun SaveProblemDialog(
    problem: SaveProblem,
    onRetry: () -> Unit,
    onOverwrite: () -> Unit,
    onReload: () -> Unit,
    onSaveCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val conflict = problem == SaveProblem.Conflict
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (conflict) R.string.conflict_title else R.string.save_failed_title)) },
        text = {
            AccessibleToToolsOnly()
            Text(
                when (problem) {
                    SaveProblem.Conflict -> stringResource(R.string.conflict_explained)
                    SaveProblem.CantWrite -> stringResource(R.string.cant_write_explained)
                    SaveProblem.WriteCheckFailed -> stringResource(R.string.write_check_failed_explained)
                    is SaveProblem.Engine -> stringResource(R.string.engine_save_failed_explained, problem.error.userMessage())
                },
            )
        },
        confirmButton = {
            // More than two choices: one per line, the safest first.
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onSaveCopy) { Text(stringResource(R.string.save_copy)) }
                if (conflict) {
                    TextButton(onClick = onOverwrite) { Text(stringResource(R.string.replace_with_mine)) }
                    TextButton(onClick = onReload) { Text(stringResource(R.string.discard_mine)) }
                } else {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) }
            }
        },
    )
}

/** Locking throws away changes that aren't saved, so ask first. */
@Composable
fun LockUnsavedDialog(onLock: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_unsaved_title)) },
        text = {
            AccessibleToToolsOnly()
            Text(stringResource(R.string.lock_unsaved_explained))
        },
        confirmButton = { TextButton(onClick = onLock) { Text(stringResource(R.string.lock_anyway)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
