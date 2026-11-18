package mn.lls.pocketkeepass

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import mn.lls.pocketkeepass.core.Deletion
import mn.lls.pocketkeepass.core.EntryDetails
import mn.lls.pocketkeepass.core.GroupListing
import mn.lls.pocketkeepass.core.engineVersion
import mn.lls.pocketkeepass.edit.EditEvent
import mn.lls.pocketkeepass.edit.EditViewModel
import mn.lls.pocketkeepass.edit.Item
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import mn.lls.pocketkeepass.ui.BrowseScreen
import mn.lls.pocketkeepass.ui.DeleteDialog
import mn.lls.pocketkeepass.ui.EntryEditScreen
import mn.lls.pocketkeepass.ui.GeneratorDialog
import mn.lls.pocketkeepass.ui.GroupDialog
import mn.lls.pocketkeepass.ui.LockUnsavedDialog
import mn.lls.pocketkeepass.ui.MoveDialog
import mn.lls.pocketkeepass.ui.QuickUnlockOfferDialog
import mn.lls.pocketkeepass.ui.SaveProblemDialog
import mn.lls.pocketkeepass.ui.UpgradeDialog
import mn.lls.pocketkeepass.ui.rememberJustSaved
import mn.lls.pocketkeepass.ui.EntryScreen
import mn.lls.pocketkeepass.ui.UnlockScreen
import mn.lls.pocketkeepass.ui.WelcomeScreen
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

class MainActivity : ComponentActivity() {
    private val viewModel: DatabaseViewModel by viewModels()

    // Registered before the activity starts, like any activity result. The result carries the
    // cipher the user unlocked, so it still works if the activity was recreated meanwhile.
    private val biometricPrompt = registerForAuthenticationResult(
        object : AuthenticationResultCallback {
            override fun onAuthResult(result: AuthenticationResult) = viewModel.onBiometricResult(result)
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording, or previews in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Keep other autofill services away from the master password: they would offer to save it.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.biometricPrompts.collect(biometricPrompt::launch)
            }
        }
        setContent {
            AccessibleToToolsOnly()
            PocketKeePassTheme { App() }
        }
    }

    private val autoLock get() = (application as PocketKeePassApp).autoLock

    // Every touch and key press, before the app sees it. Counts as using the app for auto-lock.
    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLock.onUserInteraction()
    }

    override fun onStart() {
        super.onStart()
        autoLock.onAppVisible()
        viewModel.refreshQuickUnlock() // biometrics may have been set up or removed meanwhile
    }

    override fun onStop() {
        super.onStop()
        autoLock.onAppHidden()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Closing the app for good (not a rotation) locks the database. The session would
        // otherwise keep it open for autofill until auto-lock's timeout.
        if (isFinishing) (application as PocketKeePassApp).session.lock()
    }
}

/** Every file type: providers label .kdbx files inconsistently, and key files can be anything. */
private val ANY_FILE = arrayOf("*/*")

@Composable
private fun App(viewModel: DatabaseViewModel = viewModel(), editor: EditViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoLockSettings by viewModel.autoLockSettings.collectAsStateWithLifecycle()
    val clipboardTimeout by viewModel.clipboardTimeout.collectAsStateWithLifecycle()
    val edit by editor.state.collectAsStateWithLifecycle()
    val saveState by editor.saveState.collectAsStateWithLifecycle()
    val justSaved = rememberJustSaved(saveState)
    val snackbar = remember { SnackbarHostState() }
    // Keeps the browse screen's scroll position while an entry is shown on top of it.
    val savedState = rememberSaveableStateHolder()
    val pickDatabase = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onDatabasePicked)
    }
    val pickKeyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onKeyFilePicked)
    }
    val saveBackupAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(viewModel::saveBackupAs)
    }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(editor::saveCopy)
    }

    // Why the last save failed, while its dialog is open. It opens by itself when a save fails.
    var showSaveProblem by remember { mutableStateOf(false) }
    val saveProblem = (saveState as? SaveState.Unsaved)?.problem
    LaunchedEffect(saveProblem) { showSaveProblem = saveProblem != null }
    var confirmLock by remember { mutableStateOf(false) }
    val lock = { if (saveState is SaveState.Unsaved) confirmLock = true else viewModel.lock() }

    EditEvents(editor, viewModel, snackbar)

    when (val current = state) {
        DatabaseState.NoDatabase -> WelcomeScreen(
            // Asking the Rust engine for its version also proves it loaded on this device.
            engineVersion = remember { engineVersion() },
            onOpenDatabase = { pickDatabase.launch(ANY_FILE) },
        )
        is DatabaseState.Locked -> UnlockScreen(
            state = current,
            password = viewModel.password,
            onUnlock = viewModel::unlock,
            onChangeDatabase = { pickDatabase.launch(ANY_FILE) },
            onAddKeyFile = { pickKeyFile.launch(ANY_FILE) },
            onRemoveKeyFile = viewModel::removeKeyFile,
            onQuickUnlock = viewModel::quickUnlock,
            onOfferQuickUnlock = viewModel::offerQuickUnlock,
            onRestoreBackup = viewModel::restoreBackup,
            onSaveBackupAs = { saveBackupAs.launch(current.database.name.substringBeforeLast('.') + " (restored).kdbx") },
        )
        is DatabaseState.Unlocked -> {
            val databaseName = current.summary.name.ifEmpty { current.database.name.substringBeforeLast('.') }
            val entry = current.entry
            val listing = current.listing
            val entryForm = edit.entryForm
            when {
                entryForm != null -> EntryEditScreen(
                    form = entryForm,
                    busy = edit.busy,
                    error = edit.error,
                    onSave = editor::saveEntry,
                    onClose = editor::closeEntryForm,
                    onBeginEditing = editor::beginEditing,
                    onGenerate = editor::openGenerator,
                )
                entry != null -> EntryScreen(
                    entry = entry,
                    databaseName = databaseName,
                    clipboardTimeoutSeconds = clipboardTimeout,
                    onBack = viewModel::closeEntry,
                    onLock = lock,
                    onReveal = { field -> viewModel.reveal(entry.id, field) },
                    onCopy = { field, label -> viewModel.copy(entry.id, field, label) },
                    onTotpCode = { unixSeconds -> viewModel.totpCode(entry.id, unixSeconds) },
                    onCopyTotp = { label -> viewModel.copyTotp(entry.id, label) },
                    saveState = saveState,
                    justSaved = justSaved,
                    onSaveDetails = { showSaveProblem = true },
                    snackbar = snackbar,
                    onEdit = { editor.editEntry(entry) },
                    onMove = { editor.move(entry.toItem()) },
                    onDelete = { editor.delete(entry.toItem()) },
                )
                else -> savedState.SaveableStateProvider("browse") {
                    BrowseScreen(
                        state = current,
                        searchQuery = viewModel.searchQuery,
                        onOpenGroup = viewModel::openGroup,
                        onUp = { viewModel.goUp() },
                        onOpenSearch = viewModel::openSearch,
                        onCloseSearch = viewModel::closeSearch,
                        onOpenEntry = viewModel::openEntry,
                        onLock = lock,
                        autoLockSettings = autoLockSettings,
                        onChangeAutoLock = viewModel::setAutoLock,
                        clipboardTimeoutSeconds = clipboardTimeout,
                        onChangeClipboardTimeout = viewModel::setClipboardTimeout,
                        onChangeQuickUnlock = viewModel::setQuickUnlock,
                        onOpenSettings = viewModel::refreshQuickUnlock,
                        saveState = saveState,
                        justSaved = justSaved,
                        onSaveDetails = { showSaveProblem = true },
                        snackbar = snackbar,
                        onNewEntry = { editor.newEntry(listing.group.id) },
                        onNewGroup = { editor.newGroup(listing.group.id) },
                        onEditGroup = { editor.editGroup(listing.group.id) },
                        onMoveGroup = { editor.move(listing.toItem()) },
                        onDeleteGroup = { editor.delete(listing.toItem()) },
                    )
                }
            }

            edit.generator?.let { generator ->
                GeneratorDialog(
                    state = generator,
                    onChangeOptions = editor::setGeneratorOptions,
                    onGenerateAgain = editor::generateAgain,
                    onUse = editor::useGenerated,
                    onDismiss = editor::closeGenerator,
                )
            }
            edit.groupForm?.let { form ->
                GroupDialog(form, edit.busy, edit.error, onSave = editor::saveGroup, onDismiss = editor::closeGroupForm)
            }
            edit.confirmDelete?.let { item ->
                DeleteDialog(item, current.summary.recycleBinEnabled, onConfirm = editor::confirmDelete, onDismiss = editor::cancelDelete)
            }
            edit.move?.let { choice ->
                MoveDialog(choice, databaseName, onPick = editor::moveTo, onDismiss = editor::cancelMove)
            }
            if (edit.askUpgrade != null) {
                UpgradeDialog(onConfirm = editor::confirmUpgrade, onDismiss = editor::cancelUpgrade)
            }
            if (showSaveProblem && saveProblem != null) {
                val dismiss = { showSaveProblem = false }
                SaveProblemDialog(
                    problem = saveProblem,
                    onRetry = { dismiss(); editor.retrySave() },
                    onOverwrite = { dismiss(); editor.overwrite() },
                    onReload = { dismiss(); editor.reload() },
                    onSaveCopy = {
                        dismiss()
                        saveCopy.launch(current.database.name.substringBeforeLast('.') + " (copy).kdbx")
                    },
                    onDismiss = dismiss,
                )
            }
            if (current.offerQuickUnlock) {
                QuickUnlockOfferDialog(
                    databaseName = databaseName,
                    onTurnOn = { viewModel.answerQuickUnlockOffer(turnOn = true) },
                    onDismiss = { viewModel.answerQuickUnlockOffer(turnOn = false) },
                )
            }
            if (confirmLock) {
                LockUnsavedDialog(
                    onLock = {
                        confirmLock = false
                        viewModel.lock()
                    },
                    onDismiss = { confirmLock = false },
                )
            }
        }
    }
}

/**
 * Follows up on finished changes: opens a new entry, leaves what was deleted or moved away,
 * and says what happened.
 */
@Composable
private fun EditEvents(editor: EditViewModel, viewModel: DatabaseViewModel, snackbar: SnackbarHostState) {
    // Read through rememberUpdatedState, so a message after a language or configuration change
    // comes from the current resources without restarting the collection (and missing events).
    val resources by rememberUpdatedState(LocalResources.current)
    LaunchedEffect(editor) {
        editor.events.collect { event ->
            val message = when (event) {
                is EditEvent.EntryCreated -> {
                    viewModel.openEntry(event.id)
                    null
                }
                is EditEvent.Deleted -> {
                    viewModel.leave(event.item)
                    resources.getString(
                        if (event.deletion == Deletion.MOVED_TO_RECYCLE_BIN) R.string.moved_to_recycle_bin else R.string.deleted,
                    )
                }
                is EditEvent.Moved -> resources.getString(R.string.moved)
                is EditEvent.CopySaved -> resources.getString(if (event.problem == null) R.string.copy_saved else R.string.copy_failed)
                is EditEvent.Failed -> event.message
            }
            if (message != null) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(message)
            }
        }
    }
}

private fun EntryDetails.toItem() = Item.Entry(id, title, inRecycleBin = isInRecycleBin)

private fun GroupListing.toItem() = Item.Group(
    id = group.id,
    name = group.name,
    inRecycleBin = parents.any { it.isRecycleBin },
    isRecycleBin = group.isRecycleBin,
)
