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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mn.lls.passthekey.core.engineVersion
import mn.lls.passthekey.ui.BrowseScreen
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
            PassTheKeyTheme { App() }
        }
    }
}

/** Every file type: providers label .kdbx files inconsistently, and key files can be anything. */
private val ANY_FILE = arrayOf("*/*")

@Composable
private fun App(viewModel: DatabaseViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        is DatabaseState.Unlocked -> BrowseScreen(
            state = current,
            searchQuery = viewModel.searchQuery,
            onOpenGroup = viewModel::openGroup,
            onUp = { viewModel.goUp() },
            onOpenSearch = viewModel::openSearch,
            onCloseSearch = viewModel::closeSearch,
            onLock = viewModel::lock,
        )
    }
}
