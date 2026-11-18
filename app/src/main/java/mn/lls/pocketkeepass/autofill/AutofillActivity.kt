package mn.lls.pocketkeepass.autofill

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.nio.CharBuffer
import kotlinx.coroutines.launch
import mn.lls.pocketkeepass.DatabaseState
import mn.lls.pocketkeepass.DatabaseViewModel
import mn.lls.pocketkeepass.PocketKeePassApp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.core.SearchResult
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import mn.lls.pocketkeepass.ui.AutofillScreen
import mn.lls.pocketkeepass.ui.UnlockScreen
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

/**
 * What a tap on an autofill suggestion opens, on top of the app being filled in. It ends by
 * handing Android the chosen entry's username and password for that app's form, or nothing.
 *
 * - A suggested entry, with the database unlocked: nothing is shown. The values are fetched and
 *   handed over at once (the window is see-through, so there's no flash).
 * - "Unlock PocketKeePass": the unlock screen. Once unlocked, a single linked entry is filled
 *   in directly; otherwise the user chooses.
 * - "Search PocketKeePass": the linked entries and a search box.
 *
 * Only PocketKeePass's own service can open it (it isn't exported), through pending intents
 * nobody else can alter.
 */
class AutofillActivity : ComponentActivity() {
    private val viewModel: DatabaseViewModel by viewModels()

    private val biometricPrompt = registerForAuthenticationResult(
        object : AuthenticationResultCallback {
            override fun onAuthResult(result: AuthenticationResult) = viewModel.onBiometricResult(result)
        },
    )

    private val autoLock get() = (application as PocketKeePassApp).autoLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val fill = Fill.from(intent)
        if (fill == null) {
            cancel()
            return
        }
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        val search = intent.getBooleanExtra(EXTRA_SEARCH, false)
        val target = describe(fill)

        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.biometricPrompts.collect(biometricPrompt::launch)
            }
        }
        setContent {
            AccessibleToToolsOnly()
            PocketKeePassTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                when (val current = state) {
                    // The service offers nothing until a database is chosen, so this is a stale suggestion.
                    DatabaseState.NoDatabase -> LaunchedEffect(Unit) { cancel() }
                    // Changing the database or its key file is the app's own business.
                    is DatabaseState.Locked -> UnlockScreen(
                        state = current,
                        password = viewModel.password,
                        onUnlock = viewModel::unlock,
                        onChangeDatabase = null,
                        onAddKeyFile = null,
                        onRemoveKeyFile = null,
                        onQuickUnlock = viewModel::quickUnlock,
                        onOfferQuickUnlock = viewModel::offerQuickUnlock,
                    )
                    is DatabaseState.Unlocked -> {
                        // The entries linked to the app or site. Null until they're known.
                        var linked by remember { mutableStateOf<List<SearchResult>?>(null) }
                        LaunchedEffect(Unit) {
                            if (entryId != null) {
                                fillIn(fill, entryId)
                                return@LaunchedEffect
                            }
                            // Null means it was locked meanwhile: the unlock screen takes over.
                            val matches = viewModel.autofillMatches(fill.target) ?: return@LaunchedEffect
                            if (matches.size == 1 && !search) {
                                fillIn(fill, matches[0].entry.id)
                            } else {
                                viewModel.openSearch()
                                linked = matches
                            }
                        }
                        linked?.let { matches ->
                            AutofillScreen(
                                target = target,
                                unverifiedBrowser = fill.unverifiedBrowser,
                                linked = matches,
                                searchQuery = viewModel.searchQuery,
                                results = current.results,
                                onPick = { entry -> lifecycleScope.launch { fillIn(fill, entry.id) } },
                                onCancel = ::cancel,
                            )
                        }
                    }
                }
            }
        }
    }

    // Every touch and key press counts as using the app for auto-lock, as in the app itself.
    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLock.onUserInteraction()
    }

    override fun onStart() {
        super.onStart()
        autoLock.onAppVisible()
        viewModel.refreshQuickUnlock()
    }

    override fun onStop() {
        super.onStop()
        autoLock.onAppHidden()
    }

    /**
     * Hands the entry's username and password to Android, for the fields the service found, and
     * closes. If the database was locked meanwhile, the unlock screen appears instead and this
     * runs again afterwards.
     */
    private suspend fun fillIn(fill: Fill, entryId: String) {
        val credentials = viewModel.credentials(entryId)
        if (credentials == null) {
            // Locked meanwhile: stay, and the unlock screen shows. Otherwise the entry is gone.
            if (viewModel.state.value is DatabaseState.Unlocked) cancel()
            return
        }
        try {
            // Not a String: these wrap our arrays, which are wiped below. Android makes its own
            // copy when the result leaves the app, which happens inside finish().
            val values = listOf(fill.username to credentials.username, fill.password to credentials.password)
                .filter { (field, value) -> field != null && value.isNotEmpty() }
            if (values.isEmpty()) {
                Toast.makeText(this, R.string.autofill_nothing_to_fill, Toast.LENGTH_LONG).show()
                cancel()
                return
            }
            val dataset = Dataset.Builder()
            for ((field, value) in values) dataset.setValue(field!!, AutofillValue.forText(CharBuffer.wrap(value)))
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset.build()))
            finish()
        } finally {
            credentials.wipe()
        }
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /** The website, or the app's name with its package name, for the user to check. */
    private fun describe(fill: Fill): String {
        fill.domain?.let { return it }
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(fill.packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null // Android only shows us some of the installed apps
        }
        return if (label == null || label == fill.packageName) fill.packageName else getString(R.string.autofill_app, label, fill.packageName)
    }

    companion object {
        /** The entry to fill in, from a suggestion that names one. */
        const val EXTRA_ENTRY_ID = "entry_id"

        /** Show the chooser even if exactly one entry is linked. */
        const val EXTRA_SEARCH = "search"
    }
}
