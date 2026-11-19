package mn.lls.pocketkeepass.passkey

import android.app.Application
import android.app.KeyguardManager
import androidx.annotation.RequiresApi
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationResult
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.lls.pocketkeepass.PocketKeePassApp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.SaveProblem
import mn.lls.pocketkeepass.SaveState
import mn.lls.pocketkeepass.Session
import mn.lls.pocketkeepass.autofill.TrustedBrowsers
import mn.lls.pocketkeepass.core.AppTrust
import mn.lls.pocketkeepass.core.GroupChoice
import mn.lls.pocketkeepass.core.KeePassException
import mn.lls.pocketkeepass.core.PasskeyCreation
import mn.lls.pocketkeepass.core.PasskeyTarget
import mn.lls.pocketkeepass.core.UnlockedDatabase
import mn.lls.pocketkeepass.ui.userMessage

/**
 * One passkey request, from the moment the database is open until there's an answer for
 * Android. It lives in a ViewModel so a rotation can't start it twice: creating a passkey and
 * saving it must happen once.
 *
 * Every signature and every new passkey comes after the user was verified: by unlocking the
 * database within this request, or else by the screen lock or a biometric. That's what the
 * response's "user verified" flag tells the site.
 */
@RequiresApi(34)
class PasskeyViewModel(app: Application) : AndroidViewModel(app) {
    /** What the screen shows. */
    sealed interface Step {
        /** Nothing to show: working, or waiting for the system's verification prompt. */
        data object Working : Step

        /** Asking whether to create the passkey, and where. */
        data class Create(
            val creation: PasskeyCreation,
            /** Every group a new entry can go in (not the recycle bin), in tree order. */
            val groups: List<GroupChoice>,
            /** The group offered first: the one used last, or the root group. */
            val group: String,
            /** The website, or the app's name and package. */
            val caller: String,
            val callerIsApp: Boolean,
            val needsUpgrade: Boolean,
        ) : Step

        /** Asking whether an app may use a passkey it isn't linked to yet. */
        data class Link(val app: String, val site: String, val entryTitle: String, val needsUpgrade: Boolean) : Step

        /** Waiting for the new passkey to be saved. */
        data object Saving : Step

        /** The new passkey couldn't be saved. */
        data class SaveFailed(val problem: SaveProblem) : Step

        /** It can't be done, and this says why. */
        data class Failed(val message: String) : Step
    }

    /** What goes back to Android. */
    sealed interface Outcome {
        data class SignedIn(val responseJson: String) : Outcome

        data class Created(val responseJson: String) : Outcome

        data class Entries(val response: BeginGetCredentialResponse) : Outcome

        /** A WebAuthn error for the site (its `DOMException` name), with a message. */
        data class Error(val kind: ErrorKind, val message: String) : Outcome

        /** The user said no. */
        data object Cancelled : Outcome
    }

    enum class ErrorKind { InvalidState, NotSupported, NotAllowed, Unknown }

    /** Where the user chose to put a new passkey. */
    sealed interface Placement {
        data class NewEntry(val groupId: String) : Placement

        data class InEntry(val entryId: String) : Placement
    }

    private val pocket = app as PocketKeePassApp
    private val session = pocket.session
    private val browsers = TrustedBrowsers(app)
    private val preferences = PasskeyPreferences(app)

    private val _step = MutableStateFlow<Step>(Step.Working)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)

    /** Set once, when the request is done. The activity hands it to Android and closes. */
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    private val _verifications = Channel<AuthenticationRequest>(Channel.CONFLATED)

    /** Prompts for the activity to show: the screen lock or a biometric, to verify the user. */
    val verifications: Flow<AuthenticationRequest> = _verifications.receiveAsFlow()

    private var verification: CompletableDeferred<Boolean>? = null
    private var started = false

    /**
     * The database was unlocked within this request, so the user was verified. Set by the
     * activity when the unlock screen it showed is followed by an open database, not merely
     * when it shows the unlock screen.
     */
    var unlockedHere = false

    /** The user's answer on the current screen. */
    private var answer: CompletableDeferred<Any?>? = null

    private val database: UnlockedDatabase? get() = session.open.value?.database

    /** Starts the request, once. Call when the database is open. */
    fun start(request: Request) {
        if (started) return
        started = true
        viewModelScope.launch {
            val outcome = try {
                when (request) {
                    is Request.SignIn -> signIn(request.request, request.entryId)
                    is Request.Create -> create(request.request)
                    is Request.Unlock -> entries(request.request)
                }
            } catch (_: Locked) {
                // Locked meanwhile (auto-lock). Nothing was signed or changed; start over.
                started = false
                _step.value = Step.Working
                return@launch
            }
            if (outcome != null) _outcome.value = outcome
        }
    }

    sealed interface Request {
        class SignIn(val request: ProviderGetCredentialRequest, val entryId: String) : Request

        class Create(val request: ProviderCreateCredentialRequest) : Request

        class Unlock(val request: BeginGetCredentialRequest) : Request
    }

    private suspend fun entries(request: BeginGetCredentialRequest): Outcome {
        val response = withContext(Dispatchers.Default) {
            PocketPasskeyService.signInEntries(getApplication(), request, database, browsers, includeUnlinked = true)
        } ?: throw Locked()
        return Outcome.Entries(response)
    }

    private suspend fun signIn(request: ProviderGetCredentialRequest, entryId: String): Outcome? {
        val option = request.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull()
            ?: return refuse(ErrorKind.Unknown, R.string.passkey_bad_request)
        val caller = findCaller(getApplication(), request.callingAppInfo, browsers)
            ?: return refuse(ErrorKind.NotAllowed, R.string.passkey_unknown_caller)
        val client = caller.client(option.clientDataHash) ?: return refuse(ErrorKind.Unknown, R.string.passkey_bad_request)
        val choice = engine { it.passkeysFor(option.requestJson, client) }.find { it.result.entry.id == entryId }
            ?: return fail(ErrorKind.NotAllowed, string(R.string.passkey_gone))
        choice.unusableReason?.let { return fail(ErrorKind.NotSupported, it) }
        when (choice.trust) {
            AppTrust.BROWSER, AppTrust.LINKED -> {}
            AppTrust.OTHER_SIGNER -> return fail(ErrorKind.NotAllowed, string(R.string.passkey_other_signer, caller.description))
            AppTrust.NOT_LINKED -> {
                val app = caller as Caller.App
                val needsUpgrade = engine { it.summary().needsUpgrade }
                val title = choice.result.entry.title.ifEmpty { string(R.string.untitled_entry) }
                if (!ask<Boolean>(Step.Link(caller.description, choice.rpId, title, needsUpgrade))) return Outcome.Cancelled
                if (!verify()) return Outcome.Cancelled
                engine { db ->
                    if (needsUpgrade) db.upgradeToKdbx4()
                    db.linkApp(entryId, app.packageName, app.certificates)
                }
                session.changed() // saved in the background; if it fails, the app asks again next time
                return Outcome.SignedIn(engine { it.assertPasskey(entryId, option.requestJson, client) })
            }
        }
        if (!verify()) return Outcome.Cancelled
        return Outcome.SignedIn(engine { it.assertPasskey(entryId, option.requestJson, client) })
    }

    private suspend fun create(request: ProviderCreateCredentialRequest): Outcome? {
        val call = request.callingRequest as? CreatePublicKeyCredentialRequest
            ?: return refuse(ErrorKind.Unknown, R.string.passkey_bad_request)
        val caller = findCaller(getApplication(), request.callingAppInfo, browsers)
            ?: return refuse(ErrorKind.NotAllowed, R.string.passkey_unknown_caller)
        val client = caller.client(call.clientDataHash) ?: return refuse(ErrorKind.Unknown, R.string.passkey_bad_request)
        val creation = try {
            engine { it.passkeyCreation(call.requestJson) }
        } catch (e: KeePassException.PasskeyExists) {
            return fail(ErrorKind.InvalidState, e.userMessage(getApplication<Application>().resources))
        } catch (e: KeePassException.PasskeyUnsupported) {
            return fail(ErrorKind.NotSupported, e.userMessage(getApplication<Application>().resources))
        } catch (e: KeePassException.Invalid) {
            return fail(ErrorKind.Unknown, e.userMessage(getApplication<Application>().resources))
        }
        val needsUpgrade = engine { it.summary().needsUpgrade }
        val groups = engine { it.allGroups() }.filter { !it.isRecycleBin && !it.isInRecycleBin }
        val group = groups.firstOrNull { it.id == preferences.lastGroup } ?: groups.first()
        val step = Step.Create(creation, groups, group.id, caller.description, caller is Caller.App, needsUpgrade)
        val target = when (val chosen = ask<Placement>(step)) {
            is Placement.NewEntry -> {
                preferences.lastGroup = chosen.groupId
                PasskeyTarget.NewEntry(chosen.groupId)
            }
            is Placement.InEntry -> PasskeyTarget.Entry(chosen.entryId)
        }
        if (!verify()) return Outcome.Cancelled

        // From here on nothing is cancelled halfway: the passkey is made, saved, or taken back.
        return withContext(NonCancellable) {
            val open = session.open.value ?: throw Locked()
            val created = engine { db ->
                if (needsUpgrade) db.upgradeToKdbx4()
                db.createPasskey(target, call.requestJson, client)
            }
            val count = engine { it.changeCount() }
            session.changed()
            saved(open, count, created.entryId, created.responseJson)
        }
    }

    /**
     * Waits for the new passkey to be saved, and only then answers the site: a site must never
     * keep a passkey that isn't in the file. If saving fails, the user can try again, or give up,
     * which takes the passkey back out of the database.
     *
     * "Saved" counts only for the database the passkey went into ([open]), and only once the
     * file holds the change that made it ([count], the engine's change count just after): a
     * save that was already running, or one of a database unlocked again after a lock, isn't it.
     */
    private suspend fun saved(open: Session.Open, count: ULong, entryId: String, responseJson: String): Outcome {
        while (true) {
            _step.value = Step.Saving
            val state = session.saveState.first { it != SaveState.Saving }
            if (session.open.value !== open) {
                // Locked meanwhile: the passkey, never saved, went with the rest of the database.
                return fail(ErrorKind.Unknown, string(R.string.passkey_not_saved, string(R.string.passkey_locked_before_saving)))
            }
            val problem = (state as? SaveState.Unsaved)?.problem
            if (problem == null) {
                if (open.cleanCount >= count) return Outcome.Created(responseJson)
                session.save() // the save that finished didn't have it yet
                continue
            }
            if (ask<Boolean>(Step.SaveFailed(problem))) {
                session.save()
                continue
            }
            try {
                engine { it.undoPasskey(entryId) }
                session.changed()
            } catch (_: Locked) {
                // Locked meanwhile: the unsaved passkey went with the rest of the database.
            }
            return Outcome.Cancelled
        }
    }

    /** The user's answer to [step]: the screen calls [answer] with it. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> ask(step: Step): T {
        val deferred = CompletableDeferred<Any?>()
        answer = deferred
        _step.value = step
        val result = deferred.await()
        _step.value = Step.Working
        return result as T
    }

    /** The screen's answer: true/false for yes/no, or a [Placement] for a new passkey. */
    fun answer(value: Any?) {
        answer?.complete(value)
        answer = null
    }

    /** The user closed a screen without answering. */
    fun cancel() {
        when (_step.value) {
            is Step.Failed -> answer(Unit) // the error goes back to the site
            is Step.SaveFailed -> answer(false) // takes the passkey back first
            else -> {
                answer = null
                _outcome.value = Outcome.Cancelled
            }
        }
    }

    /**
     * Verifies the user: free if the database was unlocked within this request, otherwise the
     * screen lock or a strong biometric. Without a screen lock there's nothing to verify with,
     * so passkeys can't be used.
     */
    private suspend fun verify(): Boolean {
        if (unlockedHere) return true
        val keyguard = getApplication<Application>().getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) {
            ask<Unit>(Step.Failed(string(R.string.passkey_needs_screen_lock)))
            return false
        }
        val deferred = CompletableDeferred<Boolean>()
        verification = deferred
        _verifications.trySend(
            AuthenticationRequest.biometricRequest(
                string(R.string.passkey_verify_title),
                AuthenticationRequest.Biometric.Fallback.DeviceCredential,
            ) {
                setMinStrength(AuthenticationRequest.Biometric.Strength.Class3())
            },
        )
        return deferred.await()
    }

    /** Whether a verification prompt is waiting for its result. */
    val verifying: Boolean get() = verification?.isActive == true

    fun onVerificationResult(result: AuthenticationResult) {
        verification?.complete(result.success() != null)
        verification = null
    }

    /** Refuses at once, without showing anything: the request itself is wrong. */
    private fun refuse(kind: ErrorKind, message: Int): Outcome = Outcome.Error(kind, string(message))

    /** Shows why it can't be done; closing that returns the error to the site. */
    private suspend fun fail(kind: ErrorKind, message: String): Outcome {
        ask<Unit>(Step.Failed(message))
        return Outcome.Error(kind, message)
    }

    private fun string(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    /** Calls the engine off the main thread. A lock meanwhile ends the request with [Locked]. */
    private suspend fun <T> engine(call: (UnlockedDatabase) -> T): T {
        val db = database ?: throw Locked()
        return withContext(Dispatchers.Default) {
            try {
                call(db)
            } catch (_: KeePassException.Locked) {
                throw Locked()
            } catch (_: IllegalStateException) {
                throw Locked() // closed by a lock before the call started
            }
        }
    }

    private class Locked : Exception()
}
