package mn.lls.pocketkeepass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.core.EntryItem
import mn.lls.pocketkeepass.core.SearchResult
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

/**
 * Choosing the entry to fill into another app's login form: the entries linked to that app or
 * website, or any entry the search finds. The top of the screen always says where the username
 * and password will go, since that's the one thing the user has to check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillScreen(
    /** The website, or the app's name, that will receive the entry. */
    target: String,
    /** The app reported a website, but it isn't a browser PocketKeePass can verify. */
    unverifiedBrowser: Boolean,
    linked: List<SearchResult>,
    searchQuery: TextFieldState,
    results: List<SearchResult>,
    onPick: (EntryItem) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val searching = searchQuery.text.isNotBlank()
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCancel) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel))
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                stringResource(R.string.autofill_fill_in),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(target, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(12.dp)) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // With linked entries to choose from, the keyboard stays out of their way.
                        SearchField(searchQuery, Modifier.weight(1f).padding(start = 12.dp), focusAtOnce = linked.isEmpty())
                        if (searchQuery.text.isNotEmpty()) {
                            IconButton(onClick = { searchQuery.clearText() }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear_search))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "note") {
                val note = when {
                    searching && results.isEmpty() -> stringResource(R.string.search_no_results)
                    // Picking by hand is the user vouching for the app or site, so say what happens.
                    searching -> stringResource(R.string.autofill_pick_explained, target)
                    unverifiedBrowser -> stringResource(R.string.autofill_unverified_browser)
                    linked.isEmpty() -> stringResource(R.string.autofill_none_linked, target)
                    else -> stringResource(R.string.autofill_linked)
                }
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(if (searching) results else linked, key = { it.entry.id }) { result ->
                EntryRow(
                    result.entry,
                    location = result.groupPath.joinToString(PATH_SEPARATOR),
                    onClick = { onPick(result.entry) },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AutofillScreenPreview() {
    PocketKeePassTheme {
        AutofillScreen(
            target = "login.example.com",
            unverifiedBrowser = false,
            linked = listOf(
                SearchResult(EntryItem("01", "Example", "me@example.com", "https://example.com"), listOf("Sites")),
                SearchResult(EntryItem("02", "Example (work)", "", "https://example.com"), emptyList()),
            ),
            searchQuery = TextFieldState(),
            results = emptyList(),
            onPick = {},
            onCancel = {},
        )
    }
}
