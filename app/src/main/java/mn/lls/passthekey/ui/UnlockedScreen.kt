package mn.lls.passthekey.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mn.lls.passthekey.DatabaseState
import mn.lls.passthekey.R
import mn.lls.passthekey.core.DatabaseSummary
import mn.lls.passthekey.files.Document
import mn.lls.passthekey.ui.theme.PassTheKeyTheme

/** What an open database looks like until browsing groups and entries exists (MVP feature 2). */
@Composable
fun UnlockedScreen(state: DatabaseState.Unlocked, onLock: () -> Unit) {
    val summary = state.summary
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(summary.name.ifEmpty { state.database.name }, style = MaterialTheme.typography.headlineSmall)
            val details = MaterialTheme.typography.bodyLarge
            Text(stringResource(R.string.unlocked_file, state.database.name), style = details)
            Text(stringResource(R.string.unlocked_format, summary.formatVersion), style = details)
            Text(stringResource(R.string.unlocked_groups, summary.groupCount.toInt()), style = details)
            Text(stringResource(R.string.unlocked_entries, summary.entryCount.toInt()), style = details)
            Text(
                stringResource(R.string.browsing_coming),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLock, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lock))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UnlockedScreenPreview() {
    PassTheKeyTheme {
        UnlockedScreen(
            state = DatabaseState.Unlocked(
                database = Document(Uri.EMPTY, "Passwords.kdbx"),
                keyFile = null,
                summary = DatabaseSummary("Passwords", "4.1", needsUpgrade = false, groupCount = 2u, entryCount = 14u),
            ),
            onLock = {},
        )
    }
}
