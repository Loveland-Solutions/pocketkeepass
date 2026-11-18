package mn.lls.passthekey.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mn.lls.passthekey.DatabaseState
import mn.lls.passthekey.R
import mn.lls.passthekey.core.DatabaseSummary
import mn.lls.passthekey.core.EntryItem
import mn.lls.passthekey.core.GroupItem
import mn.lls.passthekey.core.GroupListing
import mn.lls.passthekey.core.SearchResult
import mn.lls.passthekey.files.Document
import mn.lls.passthekey.ui.theme.PassTheKeyTheme

/** Separates group names in a path, e.g. "Work › Servers". */
private const val PATH_SEPARATOR = " › "

/** An open database: one group's subgroups and entries, or search results across the database. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    state: DatabaseState.Unlocked,
    searchQuery: TextFieldState,
    onOpenGroup: (id: String) -> Unit,
    onUp: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onLock: () -> Unit,
) {
    val listing = state.listing
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val entryDetailsComing = stringResource(R.string.entry_details_coming)
    val onOpenEntry: (EntryItem) -> Unit = {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(entryDetailsComing)
        }
    }

    BackHandler(enabled = state.searching, onBack = onCloseSearch)
    BackHandler(enabled = !state.searching && !listing.isRoot, onBack = onUp)

    val databaseName = state.summary.name.ifEmpty { state.database.name.substringBeforeLast('.') }
    Scaffold(
        topBar = {
            if (state.searching) {
                SearchBar(searchQuery, onCloseSearch, onLock)
            } else {
                TopAppBar(
                    navigationIcon = {
                        if (!listing.isRoot) {
                            IconButton(onClick = onUp) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                            }
                        }
                    },
                    title = { GroupTitle(listing, databaseName) },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                        }
                        IconButton(onClick = onLock) {
                            Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.lock))
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.searching) {
                SearchResults(searchQuery.text.isBlank(), state.results, onOpenEntry)
            } else {
                GroupContents(listing, onOpenGroup, onOpenEntry)
            }
        }
    }
}

@Composable
private fun GroupTitle(listing: GroupListing, databaseName: String) {
    Column {
        Text(
            if (listing.isRoot) databaseName else listing.group.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!listing.isRoot) {
            // The way back up: the database, then every group between it and this one.
            val path = listOf(databaseName) + listing.parents.drop(1).map { it.name }
            Text(
                path.joinToString(PATH_SEPARATOR),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

/**
 * The top bar while searching. It's its own row rather than a text field in `TopAppBar`'s title
 * slot: typing didn't reach a field placed there.
 */
@Composable
private fun SearchBar(query: TextFieldState, onClose: () -> Unit, onLock: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            }
            SearchField(query, Modifier.weight(1f).padding(start = 12.dp))
            if (query.text.isNotEmpty()) {
                IconButton(onClick = { query.clearText() }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear_search))
                }
            }
            IconButton(onClick = onLock) {
                Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.lock))
            }
        }
    }
}

@Composable
private fun SearchField(query: TextFieldState, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val style = MaterialTheme.typography.titleLarge
    BasicTextField(
        state = query,
        modifier = modifier.focusRequester(focus),
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorator = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.text.isEmpty()) {
                    Text(stringResource(R.string.search_hint), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                field()
            }
        },
    )
}

@Composable
private fun GroupContents(listing: GroupListing, onOpenGroup: (String) -> Unit, onOpenEntry: (EntryItem) -> Unit) {
    if (listing.groups.isEmpty() && listing.entries.isEmpty()) {
        Message(stringResource(R.string.group_empty))
        return
    }
    // A fresh list for every group, so each one opens scrolled to the top.
    val listState = remember(listing.group.id) { LazyListState() }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        items(listing.groups, key = { "g" + it.id }) { group ->
            GroupRow(group, onClick = { onOpenGroup(group.id) })
        }
        items(listing.entries, key = { "e" + it.id }) { entry ->
            EntryRow(entry, location = null, onClick = { onOpenEntry(entry) })
        }
    }
}

@Composable
private fun SearchResults(blankQuery: Boolean, results: List<SearchResult>, onOpenEntry: (EntryItem) -> Unit) {
    when {
        blankQuery -> Message(stringResource(R.string.search_explained))
        results.isEmpty() -> Message(stringResource(R.string.search_no_results))
        else -> LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(results, key = { it.entry.id }) { result ->
                EntryRow(
                    result.entry,
                    location = result.groupPath.joinToString(PATH_SEPARATOR),
                    onClick = { onOpenEntry(result.entry) },
                )
            }
        }
    }
}

@Composable
private fun GroupRow(group: GroupItem, onClick: () -> Unit) {
    val groups = pluralStringResource(R.plurals.group_count, group.groupCount.toInt(), group.groupCount.toInt())
    val entries = pluralStringResource(R.plurals.entry_count, group.entryCount.toInt(), group.entryCount.toInt())
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                painterResource(if (group.isRecycleBin) R.drawable.ic_delete else R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(group.name.ifEmpty { stringResource(R.string.untitled_group) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                when {
                    group.groupCount == 0u -> entries
                    group.entryCount == 0u -> groups
                    else -> stringResource(R.string.groups_and_entries, groups, entries)
                },
            )
        },
    )
}

/** An entry: its title, then its username (and, in search results, where it lives). */
@Composable
private fun EntryRow(entry: EntryItem, location: String?, onClick: () -> Unit) {
    val title = entry.title.ifEmpty { stringResource(R.string.untitled_entry) }
    val details = listOf(entry.username, location.orEmpty()).filter { it.isNotEmpty() }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Initial(title) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (details.isEmpty()) {
            null
        } else {
            { Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
    )
}

/** A round badge with the title's first letter, until entry icons exist. */
@Composable
private fun Initial(title: String) {
    val letter = title.trim().let { if (it.isEmpty()) "" else String(Character.toChars(it.codePointAt(0))).uppercase() }
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val previewState = DatabaseState.Unlocked(
    database = Document(Uri.EMPTY, "Passwords.kdbx"),
    keyFile = null,
    summary = DatabaseSummary("Passwords", "4.1", needsUpgrade = false, groupCount = 3u, entryCount = 4u),
    listing = GroupListing(
        group = GroupItem("01", "Root", 3u, 2u, isRecycleBin = false),
        isRoot = true,
        parents = emptyList(),
        groups = listOf(
            GroupItem("02", "Work", 1u, 3u, isRecycleBin = false),
            GroupItem("03", "Banking", 0u, 1u, isRecycleBin = false),
            GroupItem("04", "Recycle Bin", 0u, 2u, isRecycleBin = true),
        ),
        entries = listOf(
            EntryItem("05", "Email", "me@example.com", "https://mail.example.com"),
            EntryItem("06", "Router", "", ""),
        ),
    ),
)

@Preview(showBackground = true)
@Composable
private fun BrowseScreenPreview() {
    PassTheKeyTheme {
        BrowseScreen(previewState, TextFieldState(), onOpenGroup = {}, onUp = {}, onOpenSearch = {}, onCloseSearch = {}, onLock = {})
    }
}
