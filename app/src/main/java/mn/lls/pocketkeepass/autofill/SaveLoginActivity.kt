package mn.lls.pocketkeepass.autofill

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mn.lls.pocketkeepass.DatabaseState
import mn.lls.pocketkeepass.DatabaseViewModel
import mn.lls.pocketkeepass.PocketKeePassApp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.autofill.SaveLoginViewModel.Placement
import mn.lls.pocketkeepass.autofill.SaveLoginViewModel.Step
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import mn.lls.pocketkeepass.security.protectFromOverlays
import mn.lls.pocketkeepass.ui.FillDestination
import mn.lls.pocketkeepass.ui.SaveLoginFailedScreen
import mn.lls.pocketkeepass.ui.SaveLoginScreen
import mn.lls.pocketkeepass.ui.SaveLoginSaveFailedScreen
import mn.lls.pocketkeepass.ui.SaveLoginSavingScreen
import mn.lls.pocketkeepass.ui.UnlockScreen
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

/**
 * What agreeing to Android's "Save to PocketKeePass?" opens, over the app the login was typed
 * into: the unlock screen if need be, then where to save it (a new entry, or an entry already
 * linked to that app or site), then the save itself.
 *
 * Only PocketKeePass's own autofill service opens it (it isn't exported), with a token for the
 * typed login it keeps in memory ([PendingLogins]). The work is in [SaveLoginViewModel].
 */
class SaveLoginActivity : ComponentActivity() {
    private val viewModel: DatabaseViewModel by viewModels()
    private val saving: SaveLoginViewModel by viewModels()

    private val biometricPrompt = registerForAuthenticationResult(
        object : AuthenticationResultCallback {
            override fun onAuthResult(result: AuthenticationResult) = viewModel.onBiometricResult(result)
        },
    )

    private val autoLock get() = (application as PocketKeePassApp).autoLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.protectFromOverlays()
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val fill = if (saving.take(intent.getStringExtra(EXTRA_TOKEN))) saving.fill else null
        if (fill == null) {
            // Too old, or the app was restarted meanwhile: the typed login is gone.
            Toast.makeText(this, R.string.autofill_save_expired, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val destination = describe(this, fill)
        val title = defaultTitle(this, fill)

        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.biometricPrompts.collect(biometricPrompt::launch)
            }
        }
        lifecycleScope.launch {
            val outcome = saving.outcome.filterNotNull().first()
            outcome.message?.let { Toast.makeText(this@SaveLoginActivity, it, Toast.LENGTH_SHORT).show() }
            finish()
        }
        setContent {
            AccessibleToToolsOnly()
            PocketKeePassTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                when (val current = state) {
                    DatabaseState.NoDatabase -> LaunchedEffect(Unit) { saving.cancel() }
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
                        LaunchedEffect(Unit) { saving.start() }
                        val step by saving.step.collectAsStateWithLifecycle()
                        Steps(step, destination, fill.claimedDomain, title, databaseName = current.database.name)
                    }
                }
            }
        }
    }

    @Composable
    private fun Steps(step: Step, destination: FillDestination, claimedDomain: String?, title: String, databaseName: String) {
        when (step) {
            Step.Working -> Box(Modifier.fillMaxSize())
            is Step.Choose -> SaveLoginScreen(
                destination = destination,
                claimedDomain = claimedDomain,
                username = step.username,
                candidates = step.candidates,
                initialEntry = step.initialEntry,
                groups = step.groups,
                initialGroup = step.group,
                initialTitle = title,
                databaseName = databaseName,
                needsUpgrade = step.needsUpgrade,
                onSave = { entryId, groupId, newTitle ->
                    saving.answer(if (entryId != null) Placement.InEntry(entryId) else Placement.NewEntry(groupId, newTitle))
                },
                onCancel = saving::cancel,
            )
            Step.Saving -> SaveLoginSavingScreen()
            is Step.SaveFailed -> SaveLoginSaveFailedScreen(step.problem, onRetry = { saving.answer(true) }, onClose = { saving.answer(false) })
            is Step.Failed -> SaveLoginFailedScreen(step.message, onClose = saving::cancel)
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
        if (!isChangingConfigurations) viewModel.onAppHidden()
    }

    companion object {
        /** The token [PendingLogins] keeps the typed login under. */
        const val EXTRA_TOKEN = "token"
    }
}
