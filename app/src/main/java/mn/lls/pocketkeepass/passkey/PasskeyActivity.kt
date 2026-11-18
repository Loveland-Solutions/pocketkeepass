package mn.lls.pocketkeepass.passkey

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.UnknownError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import androidx.credentials.provider.PendingIntentHandler
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
import mn.lls.pocketkeepass.passkey.PasskeyViewModel.ErrorKind
import mn.lls.pocketkeepass.passkey.PasskeyViewModel.Outcome
import mn.lls.pocketkeepass.passkey.PasskeyViewModel.Request
import mn.lls.pocketkeepass.passkey.PasskeyViewModel.Step
import mn.lls.pocketkeepass.security.AccessibleToToolsOnly
import mn.lls.pocketkeepass.ui.PasskeyCreateScreen
import mn.lls.pocketkeepass.ui.PasskeyFailedScreen
import mn.lls.pocketkeepass.ui.PasskeyLinkScreen
import mn.lls.pocketkeepass.ui.PasskeySaveFailedScreen
import mn.lls.pocketkeepass.ui.PasskeySavingScreen
import mn.lls.pocketkeepass.ui.UnlockScreen
import mn.lls.pocketkeepass.ui.theme.PocketKeePassTheme

/**
 * What picking PocketKeePass in Android's passkey sheet opens, on top of the site or app that
 * asked. It unlocks the database if needed, asks the user (and verifies them), then hands
 * Android a signature, a new passkey, or the list of passkeys to choose from.
 *
 * Only Android's Credential Manager opens it, through PocketKeePass's own pending intents (it
 * isn't exported). The work is in [PasskeyViewModel]; this shows its screens and passes the
 * answer back.
 */
@RequiresApi(34)
class PasskeyActivity : ComponentActivity() {
    private val viewModel: DatabaseViewModel by viewModels()
    private val passkeys: PasskeyViewModel by viewModels()

    // One launcher for both kinds of prompt: unlocking the database, and verifying the user.
    private val prompt = registerForAuthenticationResult(
        object : AuthenticationResultCallback {
            override fun onAuthResult(result: AuthenticationResult) {
                if (passkeys.verifying) passkeys.onVerificationResult(result) else viewModel.onBiometricResult(result)
            }
        },
    )

    private val autoLock get() = (application as PocketKeePassApp).autoLock

    private var creating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val request = readRequest()
        if (request == null) {
            finishWith(Outcome.Error(ErrorKind.Unknown, ""))
            return
        }
        creating = request is Request.Create

        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.biometricPrompts.collect(prompt::launch) }
                launch { passkeys.verifications.collect(prompt::launch) }
            }
        }
        lifecycleScope.launch { finishWith(passkeys.outcome.filterNotNull().first()) }
        setContent {
            AccessibleToToolsOnly()
            PocketKeePassTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                when (val current = state) {
                    DatabaseState.NoDatabase -> LaunchedEffect(Unit) { finishWith(Outcome.Cancelled) }
                    is DatabaseState.Locked -> {
                        // Unlocking within this request verifies the user.
                        LaunchedEffect(Unit) { passkeys.unlockedHere = true }
                        UnlockScreen(
                            state = current,
                            password = viewModel.password,
                            onUnlock = viewModel::unlock,
                            onChangeDatabase = null,
                            onAddKeyFile = null,
                            onRemoveKeyFile = null,
                            onQuickUnlock = viewModel::quickUnlock,
                            onOfferQuickUnlock = viewModel::offerQuickUnlock,
                        )
                    }
                    is DatabaseState.Unlocked -> {
                        LaunchedEffect(Unit) { passkeys.start(request) }
                        val step by passkeys.step.collectAsStateWithLifecycle()
                        Steps(step, databaseName = current.database.name)
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Steps(step: Step, databaseName: String) {
        when (step) {
            Step.Working -> Box(Modifier.fillMaxSize())
            is Step.Create -> PasskeyCreateScreen(
                creation = step.creation,
                groups = step.groups,
                initialGroup = step.group,
                databaseName = databaseName,
                caller = step.caller,
                callerIsApp = step.callerIsApp,
                needsUpgrade = step.needsUpgrade,
                onCreate = { entryId, groupId ->
                    passkeys.answer(
                        if (entryId != null) PasskeyViewModel.Placement.InEntry(entryId) else PasskeyViewModel.Placement.NewEntry(groupId),
                    )
                },
                onCancel = passkeys::cancel,
            )
            is Step.Link -> PasskeyLinkScreen(
                app = step.app,
                site = step.site,
                entryTitle = step.entryTitle,
                needsUpgrade = step.needsUpgrade,
                onAllow = { passkeys.answer(true) },
                onCancel = { passkeys.answer(false) },
            )
            Step.Saving -> PasskeySavingScreen()
            is Step.SaveFailed -> PasskeySaveFailedScreen(step.problem, onRetry = { passkeys.answer(true) }, onCancel = { passkeys.answer(false) })
            is Step.Failed -> PasskeyFailedScreen(step.message, onClose = passkeys::cancel)
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

    private fun readRequest(): Request? = when (intent.action) {
        ACTION_GET -> {
            val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
            val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
            if (entryId != null && request != null) Request.SignIn(request, entryId) else null
        }
        ACTION_CREATE -> PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)?.let(Request::Create)
        ACTION_UNLOCK -> PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)?.let(Request::Unlock)
        else -> null
    }

    private fun finishWith(outcome: Outcome) {
        if (isFinishing) return
        val result = Intent()
        when (outcome) {
            is Outcome.SignedIn -> PendingIntentHandler.setGetCredentialResponse(
                result,
                GetCredentialResponse(PublicKeyCredential(outcome.responseJson)),
            )
            is Outcome.Created -> PendingIntentHandler.setCreateCredentialResponse(
                result,
                CreatePublicKeyCredentialResponse(outcome.responseJson),
            )
            is Outcome.Entries -> PendingIntentHandler.setBeginGetCredentialResponse(result, outcome.response)
            is Outcome.Error -> {
                val error = domError(outcome.kind)
                val message = outcome.message.ifEmpty { null }
                if (creating) {
                    PendingIntentHandler.setCreateCredentialException(result, CreatePublicKeyCredentialDomException(error, message))
                } else {
                    PendingIntentHandler.setGetCredentialException(result, GetPublicKeyCredentialDomException(error, message))
                }
            }
            Outcome.Cancelled -> {
                setResult(RESULT_CANCELED)
                finish()
                return
            }
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun domError(kind: ErrorKind): DomError = when (kind) {
        ErrorKind.InvalidState -> InvalidStateError()
        ErrorKind.NotSupported -> NotSupportedError()
        ErrorKind.NotAllowed -> NotAllowedError()
        ErrorKind.Unknown -> UnknownError()
    }

    companion object {
        const val ACTION_GET = "mn.lls.pocketkeepass.passkey.GET"
        const val ACTION_CREATE = "mn.lls.pocketkeepass.passkey.CREATE"
        const val ACTION_UNLOCK = "mn.lls.pocketkeepass.passkey.UNLOCK"

        /** The entry whose passkey to sign with. */
        const val EXTRA_ENTRY_ID = "entry_id"
    }
}
