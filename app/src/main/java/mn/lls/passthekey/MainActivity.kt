package mn.lls.passthekey

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mn.lls.passthekey.core.engineVersion
import mn.lls.passthekey.security.AccessibleToToolsOnly
import mn.lls.passthekey.ui.BrowseScreen
import mn.lls.passthekey.ui.EntryScreen
import mn.lls.passthekey.ui.UnlockScreen
import mn.lls.passthekey.ui.WelcomeScreen
import mn.lls.passthekey.ui.theme.PassTheKeyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording, or previews in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            AccessibleToToolsOnly()
            PassTheKeyTheme { App() }
        }
    }

    private val autoLock get() = (application as PassTheKeyApp).autoLock

    // Every touch and key press, before the app sees it. Counts as using the app for auto-lock.
    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLock.onUserInteraction()
    }

    override fun onStart() {
        super.onStart()
        autoLock.onAppVisible()
    }

    override fun onStop() {
        super.onStop()
        autoLock.onAppHidden()
    }
}

/** Every file type: providers label .kdbx files inconsistently, and key files can be anything. */
private val ANY_FILE = arrayOf("*/*")

@Composable
private fun App(viewModel: DatabaseViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoLockSettings by viewModel.autoLockSettings.collectAsStateWithLifecycle()
    val clipboardTimeout by viewModel.clipboardTimeout.collectAsStateWithLifecycle()
    // Keeps the browse screen's scroll position while an entry is shown on top of it.
    val savedState = rememberSaveableStateHolder()
    val pickDatabase = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onDatabasePicked)
    }
    val pickKeyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onKeyFilePicked)
    }

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
        )
        is DatabaseState.Unlocked -> {
            val databaseName = current.summary.name.ifEmpty { current.database.name.substringBeforeLast('.') }
            val entry = current.entry
            if (entry != null) {
                EntryScreen(
                    entry = entry,
                    databaseName = databaseName,
                    clipboardTimeoutSeconds = clipboardTimeout,
                    onBack = viewModel::closeEntry,
                    onLock = viewModel::lock,
                    onReveal = { field -> viewModel.reveal(entry.id, field) },
                    onCopy = { field, label -> viewModel.copy(entry.id, field, label) },
                )
            } else {
                savedState.SaveableStateProvider("browse") {
                    BrowseScreen(
                        state = current,
                        searchQuery = viewModel.searchQuery,
                        onOpenGroup = viewModel::openGroup,
                        onUp = { viewModel.goUp() },
                        onOpenSearch = viewModel::openSearch,
                        onCloseSearch = viewModel::closeSearch,
                        onOpenEntry = viewModel::openEntry,
                        onLock = viewModel::lock,
                        autoLockSettings = autoLockSettings,
                        onChangeAutoLock = viewModel::setAutoLock,
                        clipboardTimeoutSeconds = clipboardTimeout,
                        onChangeClipboardTimeout = viewModel::setClipboardTimeout,
                    )
                }
            }
        }
    }
}
