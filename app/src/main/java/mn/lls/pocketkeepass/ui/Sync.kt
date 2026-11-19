package mn.lls.pocketkeepass.ui

import android.content.res.Resources
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlinx.coroutines.delay
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.SyncProblem
import mn.lls.pocketkeepass.SyncStatus
import mn.lls.pocketkeepass.core.SyncReport
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly

/**
 * One line under the top bar saying whether the database is in step with the file other
 * devices share: where the file is, and when it was last checked. Tapping it explains more.
 */
@Composable
fun SyncLine(status: SyncStatus, onDetails: () -> Unit) {
    val now by rememberNow()
    val problem = status.problem
    val (icon, text) = when {
        status.checking -> R.drawable.ic_refresh to
            (status.storage?.let { stringResource(R.string.sync_checking, it) } ?: stringResource(R.string.sync_checking_phone))
        problem != null -> R.drawable.ic_cloud_off to stringResource(problemText(problem))
        else -> {
            val checked = relativeTime(status.checkedAt, now)
            if (status.storage != null) {
                R.drawable.ic_cloud_done to stringResource(R.string.sync_line_cloud, status.storage, checked)
            } else {
                R.drawable.ic_smartphone to stringResource(R.string.sync_line_phone, checked)
            }
        }
    }
    val color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onDetails)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Text(
            text,
            Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What syncing does and when it last happened, with "Check now". */
@Composable
fun SyncDialog(status: SyncStatus, onCheckNow: () -> Unit, onDismiss: () -> Unit) {
    val now by rememberNow()
    val context = LocalContext.current
    val resources = LocalResources.current
    val clock = { at: Long -> DateFormat.getTimeFormat(context).format(Date(at)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(status.storage?.let { stringResource(R.string.sync_title_cloud, it) } ?: stringResource(R.string.sync_title_phone))
        },
        text = {
            AccessibleToToolsOnly()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val lines = buildList {
                    status.problem?.let { add(resources.getString(problemText(it))) }
                    add(
                        status.savedAt?.let { resources.getString(R.string.sync_saved_at, clock(it)) }
                            ?: resources.getString(R.string.sync_nothing_saved),
                    )
                    add(resources.getString(R.string.sync_checked_at, relativeTime(status.checkedAt, now, resources)))
                    status.incoming?.let {
                        add(resources.getString(R.string.sync_incoming_at, clock(it.at), changesText(it.report, resources)))
                    }
                }
                lines.forEach { Text(it, Modifier.padding(bottom = 8.dp)) }
                Text(
                    status.storage?.let { stringResource(R.string.sync_explained_cloud, it) } ?: stringResource(R.string.sync_explained_phone),
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCheckNow, enabled = !status.checking) { Text(stringResource(R.string.check_now)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/** "Updated from Drive: 1 new entry, 2 entries changed", for a snackbar. */
fun incomingMessage(report: SyncReport, storage: String?, resources: Resources): String {
    val changes = changesText(report, resources)
    return storage?.let { resources.getString(R.string.sync_updated, it, changes) }
        ?: resources.getString(R.string.sync_updated_phone, changes)
}

/** "1 new entry, 2 entries changed": only the kinds of change there were. */
private fun changesText(report: SyncReport, resources: Resources): String = listOf(
    R.plurals.sync_entries_added to report.entriesAdded,
    R.plurals.sync_entries_changed to report.entriesChanged,
    R.plurals.sync_entries_removed to report.entriesRemoved,
    R.plurals.sync_groups_changed to report.groupsChanged,
).filter { it.second > 0u }.joinToString(", ") { (plural, count) ->
    resources.getQuantityString(plural, count.toInt(), count.toInt())
}

private fun problemText(problem: SyncProblem) = when (problem) {
    SyncProblem.CANT_READ -> R.string.sync_cant_read
    SyncProblem.KEY_CHANGED -> R.string.sync_key_changed
    SyncProblem.OTHER_DATABASE -> R.string.sync_other_database
    SyncProblem.SETTINGS_CHANGED -> R.string.sync_settings_changed
}

@Composable
private fun relativeTime(at: Long?, now: Long): String = relativeTime(at, now, LocalResources.current)

/** "just now" within the last minute, then "5 minutes ago", and so on. */
private fun relativeTime(at: Long?, now: Long, resources: Resources): String = when {
    at == null || now - at < DateUtils.MINUTE_IN_MILLIS -> resources.getString(R.string.just_now)
    else -> DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** The current time, updated every few seconds, so relative times stay right on screen. */
@Composable
private fun rememberNow() = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(TICK_MS)
        value = System.currentTimeMillis()
    }
}

private const val TICK_MS = 10_000L
