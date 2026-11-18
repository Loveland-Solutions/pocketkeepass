package mn.lls.pocketkeepass.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.SaveProblem
import mn.lls.pocketkeepass.SaveState
import mn.lls.pocketkeepass.edit.GroupForm
import mn.lls.pocketkeepass.edit.Item
import mn.lls.pocketkeepass.edit.MoveChoice
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to, name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            AccessibleToToolsOnly()
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(choice.groups, key = { it.id }) { group ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(group.id) }
                            .padding(start = (group.depth.toInt() * 20).dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_folder),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (group.depth == 0u) databaseName else group.name.ifEmpty { stringResource(R.string.untitled_group) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

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

/**
 * A thin bar under the top bar while saving, or a warning while changes aren't saved, with a
 * button for what can be done about it. Nothing when everything is saved.
 */
@Composable
fun SaveStatusBar(state: SaveState, onDetails: () -> Unit) {
    when (state) {
        SaveState.Saved -> {}
        SaveState.Saving -> Column {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.saving),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    is SaveProblem.Engine -> stringResource(R.string.engine_save_failed_explained, problem.message)
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
