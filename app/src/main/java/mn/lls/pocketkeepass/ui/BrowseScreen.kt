package mn.lls.pocketkeepass.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import mn.lls.pocketkeepass.DatabaseState
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.SaveState
import mn.lls.pocketkeepass.SyncStatus
import mn.lls.pocketkeepass.core.DatabaseSummary
import mn.lls.pocketkeepass.core.EntryItem
import mn.lls.pocketkeepass.core.GroupItem
import mn.lls.pocketkeepass.core.GroupListing
import mn.lls.pocketkeepass.core.SearchResult
import mn.lls.pocketkeepass.files.Document
import mn.lls.pocketkeepass.security.AutoLockSettings
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

/** Separates group names in a path, e.g. "Work › Servers". */
internal const val PATH_SEPARATOR = " › "

/** One choice in a list row's long-press menu. */
class RowAction(@param:StringRes val label: Int, val action: () -> Unit)

/** What a long press on an entry or group in the list offers. */
class RowActions(
    val duplicateEntry: (EntryItem) -> Unit,
    val moveEntry: (EntryItem, inRecycleBin: Boolean) -> Unit,
    val deleteEntry: (EntryItem, inRecycleBin: Boolean) -> Unit,
    val editGroup: (GroupItem) -> Unit,
    val moveGroup: (GroupItem, inRecycleBin: Boolean) -> Unit,
    val deleteGroup: (GroupItem, inRecycleBin: Boolean) -> Unit,
) {
    fun forEntry(entry: EntryItem, inRecycleBin: Boolean): List<RowAction> = buildList {
        // A copy of something deleted would land in the recycle bin too: not useful.
        if (!inRecycleBin) add(RowAction(R.string.duplicate) { duplicateEntry(entry) })
        add(RowAction(R.string.move) { moveEntry(entry, inRecycleBin) })
        add(RowAction(R.string.delete) { deleteEntry(entry, inRecycleBin) })
    }

    fun forGroup(group: GroupItem, inRecycleBin: Boolean): List<RowAction> = when {
        group.isRecycleBin -> listOf(RowAction(R.string.empty_recycle_bin) { deleteGroup(group, false) })
        else -> buildList {
            if (!inRecycleBin) add(RowAction(R.string.edit_group) { editGroup(group) })
            add(RowAction(R.string.move) { moveGroup(group, inRecycleBin) })
            add(RowAction(R.string.delete) { deleteGroup(group, inRecycleBin) })
        }
    }
}

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
    onOpenEntry: (id: String) -> Unit,
    onLock: () -> Unit,
    autoLockSettings: AutoLockSettings,
    onChangeAutoLock: (AutoLockSettings) -> Unit,
    clipboardTimeoutSeconds: Int,
    onChangeClipboardTimeout: (Int) -> Unit,
    onChangeQuickUnlock: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    saveState: SaveState,
    /** A save just finished: say so for a moment. */
    justSaved: Boolean,
    onSaveDetails: () -> Unit,
    snackbar: SnackbarHostState,
    onNewEntry: () -> Unit,
    onNewGroup: () -> Unit,
    onEditGroup: () -> Unit,
    onMoveGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    rowActions: RowActions,
    sync: SyncStatus,
    onCheckNow: () -> Unit,
) {
    val listing = state.listing
    val inRecycleBin = listing.group.isRecycleBin || listing.parents.any { it.isRecycleBin }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsDialog(
            autoLockSettings,
            onChangeAutoLock,
            clipboardTimeoutSeconds,
            onChangeClipboardTimeout,
            quickUnlock = state.quickUnlock,
            onChangeQuickUnlock = onChangeQuickUnlock,
            onDismiss = { showSettings = false },
        )
    }
    var showSync by remember { mutableStateOf(false) }
    if (showSync) {
        SyncDialog(sync, onCheckNow = onCheckNow, onDismiss = { showSync = false })
    }
    val onOpenEntry: (EntryItem) -> Unit = { onOpenEntry(it.id) }

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
                        IconButton(onClick = {
                            onOpenSettings()
                            showSettings = true
                        }) {
                            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                        }
                        IconButton(onClick = onLock) {
                            Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.lock))
                        }
                        if (!listing.isRoot) {
                            GroupMenu(listing.group.isRecycleBin, onEditGroup, onMoveGroup, onDeleteGroup)
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Nothing is created in the recycle bin: things only end up there by being deleted.
            if (!state.searching && !inRecycleBin) NewItemButton(onNewEntry, onNewGroup)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            SaveStatusBar(saveState, justSaved, onSaveDetails)
            SyncLine(sync, onDetails = { showSync = true })
            // Pulling the list down checks the file for changes made on other devices.
            PullToRefreshBox(isRefreshing = sync.checking, onRefresh = onCheckNow, modifier = Modifier.weight(1f)) {
                if (state.searching) {
                    SearchResults(searchQuery.text.isBlank(), state.results, onOpenEntry, rowActions)
                } else {
                    GroupContents(listing, inRecycleBin, onOpenGroup, onOpenEntry, rowActions)
                }
            }
        }
    }
}

/** "+": a new entry or a new group, in the group being shown. */
@Composable
private fun NewItemButton(onNewEntry: () -> Unit, onNewGroup: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.new_entry)) },
                onClick = {
                    open = false
                    onNewEntry()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.new_group)) },
                onClick = {
                    open = false
                    onNewGroup()
                },
            )
        }
    }
}

/** The group being shown: edit, move, or delete it. The recycle bin can only be emptied. */
@Composable
private fun GroupMenu(isRecycleBin: Boolean, onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item = @Composable { label: Int, action: () -> Unit ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
            if (isRecycleBin) {
                item(R.string.empty_recycle_bin, onDelete)
            } else {
                item(R.string.edit_group, onEdit)
                item(R.string.move, onMove)
                item(R.string.delete, onDelete)
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
internal fun SearchField(query: TextFieldState, modifier: Modifier, focusAtOnce: Boolean = true) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusAtOnce) focus.requestFocus() }
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
private fun GroupContents(
    listing: GroupListing,
    inRecycleBin: Boolean,
    onOpenGroup: (String) -> Unit,
    onOpenEntry: (EntryItem) -> Unit,
    rowActions: RowActions,
) {
    if (listing.groups.isEmpty() && listing.entries.isEmpty()) {
        // Scrollable, so pull to refresh works on an empty group too.
        LazyColumn(Modifier.fillMaxSize()) { item { Message(stringResource(R.string.group_empty)) } }
        return
    }
    // A fresh list for every group, so each one opens scrolled to the top. Saveable, so coming
    // back from an entry returns to the same place.
    val listState = rememberSaveable(listing.group.id, saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        items(listing.groups, key = { "g" + it.id }) { group ->
            GroupRow(group, onClick = { onOpenGroup(group.id) }, actions = rowActions.forGroup(group, inRecycleBin))
        }
        items(listing.entries, key = { "e" + it.id }) { entry ->
            EntryRow(entry, location = null, onClick = { onOpenEntry(entry) }, actions = rowActions.forEntry(entry, inRecycleBin))
        }
    }
}

@Composable
private fun SearchResults(
    blankQuery: Boolean,
    results: List<SearchResult>,
    onOpenEntry: (EntryItem) -> Unit,
    rowActions: RowActions,
) {
    when {
        blankQuery -> LazyColumn(Modifier.fillMaxSize()) { item { Message(stringResource(R.string.search_explained)) } }
        results.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) { item { Message(stringResource(R.string.search_no_results)) } }
        else -> LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(results, key = { it.entry.id }) { result ->
                EntryRow(
                    result.entry,
                    location = result.groupPath.joinToString(PATH_SEPARATOR),
                    onClick = { onOpenEntry(result.entry) },
                    // Search leaves the recycle bin out.
                    actions = rowActions.forEntry(result.entry, inRecycleBin = false),
                )
            }
        }
    }
}

@Composable
private fun GroupRow(group: GroupItem, onClick: () -> Unit, actions: List<RowAction>) {
    val groups = pluralStringResource(R.plurals.group_count, group.groupCount.toInt(), group.groupCount.toInt())
    val entries = pluralStringResource(R.plurals.entry_count, group.entryCount.toInt(), group.entryCount.toInt())
    WithRowMenu(actions, onClick) { modifier ->
        ListItem(
            modifier = modifier,
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
}

/**
 * A list row that opens with a tap and offers [actions] in a menu on a long press (screen
 * readers announce it as "More options").
 */
@Composable
private fun WithRowMenu(actions: List<RowAction>, onClick: () -> Unit, row: @Composable (Modifier) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        row(
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = if (actions.isEmpty()) null else ({ open = true }),
                onLongClickLabel = stringResource(R.string.more),
            ),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    onClick = {
                        open = false
                        action.action()
                    },
                )
            }
        }
    }
}

/** An entry: its title, then its username (and, in search results, where it lives). */
@Composable
internal fun EntryRow(entry: EntryItem, location: String?, onClick: () -> Unit, actions: List<RowAction> = emptyList()) {
    val title = entry.title.ifEmpty { stringResource(R.string.untitled_entry) }
    val details = listOf(entry.username, location.orEmpty()).filter { it.isNotEmpty() }
    WithRowMenu(actions, onClick) { modifier ->
        ListItem(
            modifier = modifier,
            leadingContent = { Initial(title) },
            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (details.isEmpty()) {
                null
            } else {
                { Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
        )
    }
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
    summary = DatabaseSummary("Passwords", "4.1", needsUpgrade = false, groupCount = 3u, entryCount = 4u, recycleBinEnabled = true),
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
    PocketKeePassTheme {
        BrowseScreen(
            previewState,
            TextFieldState(),
            onOpenGroup = {},
            onUp = {},
            onOpenSearch = {},
            onCloseSearch = {},
            onOpenEntry = {},
            onLock = {},
            autoLockSettings = AutoLockSettings.DEFAULT,
            onChangeAutoLock = {},
            clipboardTimeoutSeconds = 30,
            onChangeClipboardTimeout = {},
            onChangeQuickUnlock = {},
            onOpenSettings = {},
            saveState = SaveState.Saved,
            justSaved = false,
            onSaveDetails = {},
            snackbar = SnackbarHostState(),
            onNewEntry = {},
            onNewGroup = {},
            onEditGroup = {},
            onMoveGroup = {},
            onDeleteGroup = {},
            rowActions = RowActions({}, { _, _ -> }, { _, _ -> }, {}, { _, _ -> }, { _, _ -> }),
            sync = SyncStatus(storage = "Drive", checkedAt = System.currentTimeMillis()),
            onCheckNow = {},
        )
    }
}
