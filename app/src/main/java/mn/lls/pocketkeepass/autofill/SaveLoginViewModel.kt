package mn.lls.pocketkeepass.autofill

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.lls.pocketkeepass.PocketKeePassApp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.SaveProblem
import mn.lls.pocketkeepass.SaveState
import mn.lls.pocketkeepass.core.GroupChoice
import mn.lls.pocketkeepass.core.KeePassException
import mn.lls.pocketkeepass.core.LoginCandidate
import mn.lls.pocketkeepass.core.LoginPlace
import mn.lls.pocketkeepass.core.UnlockedDatabase
import mn.lls.pocketkeepass.ui.userMessage

/**
 * Saving one login the user typed into another app, from the moment the database is open until
 * it's in the file. It lives in a ViewModel so a rotation can't save it twice, and it holds the
 * typed values ([PendingLogins.Login]) until it's done, then wipes them.
 */
class SaveLoginViewModel(app: Application) : AndroidViewModel(app) {
    sealed interface Step {
        /** Nothing to show: working. */
        data object Working : Step

        /** Asking where the login goes. */
        data class Choose(
            /** What was typed as the username. Empty if nothing was. */
            val username: String,
            /** Entries already linked to the app or site, best first. */
            val candidates: List<LoginCandidate>,
            /** The entry offered first, or null for a new entry. */
            val initialEntry: String?,
            /** Every group a new entry can go in (not the recycle bin), in tree order. */
            val groups: List<GroupChoice>,
            /** The group offered first: the one used last, or the root group. */
            val group: String,
            val needsUpgrade: Boolean,
        ) : Step

        /** Waiting for the file to be written. */
        data object Saving : Step

        /** It's in the database, but not in the file. */
        data class SaveFailed(val problem: SaveProblem) : Step

        /** It can't be saved, and this says why. */
        data class Failed(val message: String) : Step
    }

    /** How it ended, for a short message as the screen closes. Null: nothing to say. */
    data class Outcome(val message: Int?)

    /** Where the user chose to put the login. */
    sealed interface Placement {
        data class NewEntry(val groupId: String, val title: String) : Placement

        data class InEntry(val entryId: String) : Placement
    }

    private val pocket = app as PocketKeePassApp
    private val session = pocket.session
    private val preferences = AutofillPreferences(app)

    private val _step = MutableStateFlow<Step>(Step.Working)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)

    /** Set once, when it's done. The activity closes then. */
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    private var login: PendingLogins.Login? = null
    private var started = false
    private var answer: CompletableDeferred<Any?>? = null

    /** Takes the login kept under [token], once. False if it's gone (too old, or the app restarted). */
    fun take(token: String?): Boolean {
        if (login == null && token != null) login = pocket.pendingLogins.take(token)
        return login != null
    }

    /** The form the login was typed into. Call after [take]. */
    val fill: Fill? get() = login?.fill

    /** Starts, once. Call when the database is open. */
    fun start() {
        val login = login ?: return
        if (started) return
        started = true
        viewModelScope.launch {
            val outcome = try {
                save(login)
            } catch (_: Locked) {
                // Locked meanwhile (auto-lock): nothing was changed. Start over once unlocked.
                started = false
                _step.value = Step.Working
                return@launch
            }
            _outcome.value = outcome
        }
    }

    private suspend fun save(login: PendingLogins.Login): Outcome {
        val target = login.fill.target
        val candidates = engine { it.loginCandidates(target, login.username, login.password) }
        if (candidates.any { it.samePassword }) return Outcome(R.string.autofill_save_already)
        val needsUpgrade = engine { it.summary().needsUpgrade }
        val groups = engine { it.allGroups() }.filter { !it.isRecycleBin && !it.isInRecycleBin }
        val group = groups.firstOrNull { it.id == preferences.lastGroup } ?: groups.first()
        val step = Step.Choose(
            username = String(login.username, Charsets.UTF_8),
            candidates = candidates,
            // Same username, different password: most likely a changed password.
            initialEntry = candidates.firstOrNull { it.sameUsername }?.result?.entry?.id,
            groups = groups,
            group = group.id,
            needsUpgrade = needsUpgrade,
        )
        val place = when (val chosen = ask<Placement?>(step) ?: return Outcome(null)) {
            is Placement.NewEntry -> {
                preferences.lastGroup = chosen.groupId
                LoginPlace.NewEntry(chosen.groupId, chosen.title)
            }
            is Placement.InEntry -> LoginPlace.Entry(chosen.entryId)
        }

        // From here on nothing is cancelled halfway: the login goes in, then it's saved.
        return withContext(NonCancellable) {
            val open = session.open.value ?: throw Locked()
            try {
                engine { db ->
                    if (needsUpgrade) db.upgradeToKdbx4()
                    db.saveLogin(target, place, login.username, login.password)
                }
            } catch (e: KeePassException.Invalid) {
                return@withContext fail(e.userMessage(getApplication<Application>().resources))
            }
            val count = engine { it.changeCount() }
            session.changed()
            saved(open, count)
        }
    }

    /**
     * Waits until the file holds the change that saved the login ([count], the engine's change
     * count just after it), for the database it went into ([open]). Until then it's only in
     * memory, and auto-lock would throw it away.
     */
    private suspend fun saved(open: mn.lls.pocketkeepass.Session.Open, count: ULong): Outcome {
        while (true) {
            _step.value = Step.Saving
            val state = session.saveState.first { it != SaveState.Saving }
            if (session.open.value !== open) return fail(string(R.string.autofill_save_locked))
            when (state) {
                // Kept on this phone until it's back online, and safe meanwhile.
                SaveState.Waiting -> return Outcome(R.string.autofill_saved_waiting)
                is SaveState.Unsaved -> {
                    if (ask<Boolean>(Step.SaveFailed(state.problem))) {
                        session.save()
                        continue
                    }
                    // It stays in the open database, and the app's own bar says it isn't saved.
                    return Outcome(null)
                }
                else -> {
                    if (open.cleanCount >= count) return Outcome(R.string.autofill_saved)
                    session.save() // the save that finished didn't have it yet
                }
            }
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

    /** The screen's answer: a [Placement] (or null to cancel), or true/false to try again. */
    fun answer(value: Any?) {
        answer?.complete(value)
        answer = null
    }

    /** The user closed the screen. */
    fun cancel() {
        when (_step.value) {
            is Step.Choose -> answer(null)
            is Step.SaveFailed -> answer(false)
            is Step.Failed -> answer(Unit)
            Step.Saving -> {} // can't be stopped halfway
            Step.Working -> _outcome.value = Outcome(null)
        }
    }

    private suspend fun fail(message: String): Outcome {
        ask<Unit>(Step.Failed(message))
        return Outcome(null)
    }

    private fun string(id: Int): String = getApplication<Application>().getString(id)

    /** Calls the engine off the main thread. A lock meanwhile ends it with [Locked]. */
    private suspend fun <T> engine(call: (UnlockedDatabase) -> T): T {
        val db = session.open.value?.database ?: throw Locked()
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

    override fun onCleared() {
        login?.wipe()
        login = null
    }

    private class Locked : Exception()
}
