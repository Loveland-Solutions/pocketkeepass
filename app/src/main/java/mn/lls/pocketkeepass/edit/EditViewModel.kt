package mn.lls.pocketkeepass.edit

import android.app.Application
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.nio.CharBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.lls.pocketkeepass.PocketKeePassApp
import mn.lls.pocketkeepass.R
import mn.lls.pocketkeepass.SaveProblem
import mn.lls.pocketkeepass.core.Deletion
import mn.lls.pocketkeepass.core.EntryDetails
import mn.lls.pocketkeepass.core.GroupChoice
import mn.lls.pocketkeepass.core.KeePassException
import mn.lls.pocketkeepass.core.PasswordOptions
import mn.lls.pocketkeepass.core.TotpSetupResult
import mn.lls.pocketkeepass.core.UnlockedDatabase
import mn.lls.pocketkeepass.core.generatePassword
import mn.lls.pocketkeepass.core.passwordStrengthBits
import mn.lls.pocketkeepass.core.totpSetup
import mn.lls.pocketkeepass.security.decodeUtf8
import mn.lls.pocketkeepass.security.toUtf8Bytes
import mn.lls.pocketkeepass.security.wipe
import mn.lls.pocketkeepass.ui.userMessage

/** An entry or group to delete or move. */
sealed interface Item {
    val id: String
    val name: String

    /** Whether it's in the recycle bin already, where deleting is for good. */
    val inRecycleBin: Boolean

    data class Entry(override val id: String, override val name: String, override val inRecycleBin: Boolean) : Item

    data class Group(
        override val id: String,
        override val name: String,
        override val inRecycleBin: Boolean,
        val isRecycleBin: Boolean,
    ) : Item
}

/** Something the user asked to change, waiting for them to agree to upgrade a KDBX 3 file first. */
sealed interface PendingEdit {
    data class NewEntry(val groupId: String) : PendingEdit
    data class EditEntry(val entry: EntryDetails) : PendingEdit
    data class NewGroup(val parentId: String) : PendingEdit
    data class EditGroup(val groupId: String) : PendingEdit
    data class Delete(val item: Item) : PendingEdit
    data class Move(val item: Item) : PendingEdit
    data class Duplicate(val entry: Item.Entry) : PendingEdit
}

/**
 * The password generator, open for [field] of the entry editor. [password] is what it made,
 * for showing, or null with [error] when the options make no password (no kind chosen).
 *
 * It has to be a `String` for Compose to draw it, so it lives only while the dialog is open.
 * The copy that goes into the field is a `CharArray`, wiped once it's there.
 */
data class GeneratorState(
    val field: FormField,
    val options: PasswordOptions,
    val password: String?,
    val strengthBits: Double?,
    val error: String?,
)

/**
 * Setting up a one-time code for the entry editor: what the user pasted ([input], a secure
 * field's state, wiped when the dialog closes), and what it makes: the current [code] to enter
 * on the site, or why it can't be used ([error]). [code] is a `String` for Compose to draw, held
 * only while the dialog is open, like a shown code on the entry screen.
 */
data class TotpSetupState(
    val input: TextFieldState,
    val code: String? = null,
    val periodSeconds: Int = 30,
    val error: String? = null,
    /** The form already has a one-time code that "Add" would replace. */
    val replaces: Boolean = false,
)

/** Choosing where to move [item]: [groups] are the places it can go, drawn as a tree. */
data class MoveChoice(val item: Item, val groups: List<GroupChoice>)

data class EditState(
    /** The entry editor, while it's open. */
    val entryForm: EntryForm? = null,
    /** The password generator, while it's open over the entry editor. */
    val generator: GeneratorState? = null,
    /** Setting up a one-time code, while it's open over the entry editor. */
    val totpSetup: TotpSetupState? = null,
    /** The group dialog, while it's open. */
    val groupForm: GroupForm? = null,
    /** Asking whether to delete this. */
    val confirmDelete: Item? = null,
    /** Choosing where to move something. */
    val move: MoveChoice? = null,
    /** Asking whether to upgrade a KDBX 3 file so that it can be changed. */
    val askUpgrade: PendingEdit? = null,
    /** A change is running in the engine. */
    val busy: Boolean = false,
    /** Why the engine refused the last change (a user-facing sentence), shown in the form. */
    val error: String? = null,
)

/** What a change did, for the screens to follow (open the new entry, leave a deleted group). */
sealed interface EditEvent {
    data class EntryCreated(val id: String) : EditEvent
    data class Duplicated(val original: Item.Entry, val copyId: String) : EditEvent
    data class Deleted(val item: Item, val deletion: Deletion) : EditEvent
    data class Moved(val item: Item) : EditEvent
    data class CopySaved(val problem: SaveProblem?) : EditEvent

    /** A change the engine refused, outside a form (which shows it itself). */
    data class Failed(val message: String) : EditEvent
}

/**
 * Changing the open database: the entry editor, the group dialog, deleting, and moving.
 *
 * Every change goes to the engine off the main thread and, once it's made, is saved at once by
 * the [mn.lls.pocketkeepass.Session]. A change that has started always finishes and is always
 * saved, even if this screen goes away meanwhile.
 */
class EditViewModel(app: Application) : AndroidViewModel(app) {
    private val session = (app as PocketKeePassApp).session
    private val clipboard = (app as PocketKeePassApp).clipboard

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    private val _events = Channel<EditEvent>(Channel.BUFFERED)
    val events: Flow<EditEvent> = _events.receiveAsFlow()

    val saveState = session.saveState

    private val database: UnlockedDatabase? get() = session.open.value?.database

    private val generatorPreferences = GeneratorPreferences(app)

    /** The generator's current password, for putting into the field. Wiped when replaced. */
    private var generated: CharArray? = null

    init {
        // Locking closes every form and dialog, and wipes what was typed.
        viewModelScope.launch {
            session.open.collect { if (it == null) reset() }
        }
    }

    fun newEntry(groupId: String) = begin(PendingEdit.NewEntry(groupId))
    fun editEntry(entry: EntryDetails) = begin(PendingEdit.EditEntry(entry))
    fun newGroup(parentId: String) = begin(PendingEdit.NewGroup(parentId))
    fun editGroup(groupId: String) = begin(PendingEdit.EditGroup(groupId))
    fun delete(item: Item) = begin(PendingEdit.Delete(item))
    fun move(item: Item) = begin(PendingEdit.Move(item))
    fun duplicate(entry: Item.Entry) = begin(PendingEdit.Duplicate(entry))

    /** Starts an edit, after asking to upgrade if the file is KDBX 3 (which can't be saved). */
    private fun begin(edit: PendingEdit) {
        val db = database ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            val needsUpgrade = engine { db.summary().needsUpgrade } ?: return@launch
            if (needsUpgrade) {
                _state.update { it.copy(askUpgrade = edit) }
            } else {
                perform(edit)
            }
        }
    }

    /**
     * The user agreed: converts the database to KDBX 4 in memory, then carries on with the edit.
     * The file itself changes with the first save.
     */
    fun confirmUpgrade() {
        val edit = _state.value.askUpgrade ?: return
        val db = database ?: return
        _state.update { it.copy(askUpgrade = null) }
        viewModelScope.launch {
            val upgraded = engine { db.upgradeToKdbx4() } != null
            if (upgraded) perform(edit)
        }
    }

    fun cancelUpgrade() = _state.update { it.copy(askUpgrade = null) }

    private suspend fun perform(edit: PendingEdit) {
        val db = database ?: return
        when (edit) {
            is PendingEdit.NewEntry -> openEntryForm(EntryForm.new(edit.groupId))
            is PendingEdit.EditEntry -> openEntryForm(EntryForm.edit(edit.entry))
            is PendingEdit.NewGroup -> openGroupForm(GroupForm.new(edit.parentId))
            is PendingEdit.EditGroup -> {
                val group = engine { db.groupDetails(edit.groupId) } ?: return
                openGroupForm(GroupForm.edit(group.id, group.name, group.notes))
            }
            is PendingEdit.Delete -> _state.update { it.copy(confirmDelete = edit.item) }
            is PendingEdit.Move -> {
                val groups = engine { db.allGroups() } ?: return
                _state.update { it.copy(move = MoveChoice(edit.item, moveTargets(edit.item, groups))) }
            }
            is PendingEdit.Duplicate -> change(
                block = { it.duplicateEntry(edit.entry.id) to true },
                onDone = { copyId -> _events.trySend(EditEvent.Duplicated(edit.entry, copyId)) },
                inForm = false,
            )
        }
    }

    private fun openEntryForm(form: EntryForm) {
        _state.value.entryForm?.wipe()
        wipeGenerated()
        _state.update { it.copy(entryForm = form, generator = null, error = null) }
    }

    private fun openGroupForm(form: GroupForm) {
        _state.value.groupForm?.wipe()
        _state.update { it.copy(groupForm = form, error = null) }
    }

    /**
     * Fetches a protected value into the form so the user can change it. Until then the form
     * has none, and saving keeps the value as it is.
     */
    fun beginEditing(field: FormField) {
        val form = _state.value.entryForm ?: return
        val entryId = form.entryId
        val key = field.previousKey
        if (entryId == null || key == null) {
            field.beginEditing("")
            return
        }
        val db = database ?: return
        viewModelScope.launch {
            val bytes = engine { db.revealField(entryId, key) } ?: return@launch
            val chars = try {
                bytes.decodeUtf8()
            } finally {
                bytes.wipe()
            }
            try {
                // Unless the generator filled the field in meanwhile.
                if (_state.value.entryForm === form && field.value == null) field.beginEditing(CharBuffer.wrap(chars))
            } finally {
                chars.wipe()
            }
        }
    }

    /**
     * Opens the generator for [field], with the options it was last used with. Using its
     * password replaces the field's value, so an existing password is never fetched.
     */
    fun openGenerator(field: FormField) {
        if (_state.value.entryForm == null) return
        generate(field, generatorPreferences.load())
    }

    fun setGeneratorOptions(options: PasswordOptions) {
        val generator = _state.value.generator ?: return
        generatorPreferences.save(options)
        generate(generator.field, options)
    }

    fun generateAgain() {
        val generator = _state.value.generator ?: return
        generate(generator.field, generator.options)
    }

    /** Puts the generated password into the field and closes the generator. */
    fun useGenerated() {
        val generator = _state.value.generator ?: return
        val chars = generated ?: return
        generator.field.beginEditing(CharBuffer.wrap(chars))
        closeGenerator()
    }

    /**
     * Copies the generator's password, so it can be pasted into a site's "new password" form
     * before the entry is saved. Marked sensitive and cleared after the usual timeout, like any
     * copy from the entry screen.
     */
    fun copyGenerated(label: String) {
        val chars = generated ?: return
        copySecret(label, CharBuffer.wrap(chars))
    }

    /**
     * Copies a protected value being edited. Android's secure text field won't copy its own
     * text, so without this a new password couldn't be pasted anywhere until the entry was saved
     * and opened again.
     */
    fun copyValue(field: FormField, label: String) {
        val value = field.value ?: return // not fetched: nothing to copy
        copySecret(label, value.text)
    }

    /** Copies straight from the buffer we hold; Android makes its own copy, which we can't wipe. */
    private fun copySecret(label: String, text: CharSequence) {
        if (text.isEmpty()) return
        clipboard.copy(label, text, sensitive = true)
        // Android 13+ confirms a copy itself. Older versions don't. A toast, because the
        // generator is a dialog, which would cover a snackbar.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val resources = getApplication<Application>().resources
            val seconds = clipboard.timeoutSeconds.value
            val clearsIn = if (seconds % 60 == 0) {
                resources.getQuantityString(R.plurals.minutes, seconds / 60, seconds / 60)
            } else {
                resources.getQuantityString(R.plurals.seconds, seconds, seconds)
            }
            Toast.makeText(getApplication(), resources.getString(R.string.copied, label, clearsIn), Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the one-time code setup for the entry being edited. */
    fun openTotpSetup() {
        val form = _state.value.entryForm ?: return
        _state.update { it.copy(totpSetup = TotpSetupState(TextFieldState(), replaces = form.hasTotp())) }
    }

    /**
     * Checks what's in the setup field and shows the code it makes, or why it can't be used.
     * Called as the user types or pastes, and when the code rolls over. On the main thread: the
     * engine only parses text and computes one HMAC.
     */
    fun checkTotpSetup() {
        val setup = _state.value.totpSetup ?: return
        if (setup.input.text.isBlank()) {
            _state.update { it.copy(totpSetup = setup.copy(code = null, error = null)) }
            return
        }
        val checked = try {
            val result = runTotpSetup(setup) ?: return
            result.value.wipe()
            val code = try {
                String(result.code.decodeUtf8())
            } finally {
                result.code.wipe()
            }
            setup.copy(code = code, periodSeconds = result.periodSeconds.toInt(), error = null)
        } catch (e: KeePassException) {
            setup.copy(code = null, error = e.userMessage(getApplication<Application>().resources))
        }
        _state.update { it.copy(totpSetup = checked) }
    }

    /** Puts the one-time code into the form's `otp` field and closes the setup. Saved with the entry. */
    fun useTotpSetup() {
        val setup = _state.value.totpSetup ?: return
        val form = _state.value.entryForm ?: return
        val result = try {
            runTotpSetup(setup) ?: return
        } catch (e: KeePassException) {
            _state.update { it.copy(totpSetup = setup.copy(code = null, error = e.userMessage(getApplication<Application>().resources))) }
            return
        }
        result.code.wipe()
        val chars = try {
            result.value.decodeUtf8()
        } finally {
            result.value.wipe()
        }
        try {
            form.setTotp(result.field, CharBuffer.wrap(chars))
        } finally {
            chars.wipe()
        }
        closeTotpSetup()
    }

    fun closeTotpSetup() {
        _state.value.totpSetup?.input?.wipeText()
        _state.update { it.copy(totpSetup = null) }
    }

    /** The engine's check of what was pasted, labelled with the form's title and username. */
    private fun runTotpSetup(setup: TotpSetupState): TotpSetupResult? {
        val form = _state.value.entryForm ?: return null
        val input = setup.input.text.toUtf8Bytes()
        return try {
            totpSetup(input, form.text(EntryForm.TITLE), form.text(EntryForm.USERNAME), (System.currentTimeMillis() / 1000).toULong())
        } finally {
            input.wipe()
        }
    }

    fun closeGenerator() {
        wipeGenerated()
        _state.update { it.copy(generator = null) }
    }

    /**
     * Makes a new password. On the main thread: the engine only draws random characters, which
     * takes microseconds, and the slider then follows the finger without lagging behind.
     */
    private fun generate(field: FormField, options: PasswordOptions) {
        wipeGenerated()
        val state = try {
            val bytes = generatePassword(options)
            val chars = try {
                bytes.decodeUtf8()
            } finally {
                bytes.wipe()
            }
            generated = chars
            GeneratorState(field, options, String(chars), passwordStrengthBits(options), error = null)
        } catch (e: KeePassException) {
            GeneratorState(field, options, password = null, strengthBits = null, error = e.userMessage(getApplication<Application>().resources))
        }
        _state.update { it.copy(generator = state) }
    }

    private fun wipeGenerated() {
        generated?.wipe()
        generated = null
    }

    fun saveEntry() {
        val form = _state.value.entryForm ?: return
        val edits = form.toEdits()
        val tags = form.tagList()
        change(
            wipe = { edits.forEach { it.value?.wipe() } },
            block = { db ->
                if (form.isNew) {
                    db.createEntry(form.groupId!!, edits, tags) to true
                } else {
                    form.entryId!! to db.updateEntry(form.entryId, edits, tags)
                }
            },
            onDone = { id ->
                closeEntryForm()
                if (form.isNew) _events.trySend(EditEvent.EntryCreated(id))
            },
        )
    }

    fun closeEntryForm() {
        _state.value.entryForm?.wipe()
        _state.value.totpSetup?.input?.wipeText()
        wipeGenerated()
        _state.update { it.copy(entryForm = null, generator = null, totpSetup = null, error = null) }
    }

    fun saveGroup() {
        val form = _state.value.groupForm ?: return
        val name = form.name.text.toString().trim()
        val notes = form.notes.text.toString()
        change(
            block = { db ->
                if (form.isNew) {
                    db.createGroup(form.parentId!!, name, notes)
                    Unit to true
                } else {
                    Unit to db.updateGroup(form.groupId!!, name, notes)
                }
            },
            onDone = { closeGroupForm() },
        )
    }

    fun closeGroupForm() {
        _state.value.groupForm?.wipe()
        _state.update { it.copy(groupForm = null, error = null) }
    }

    fun confirmDelete() {
        val item = _state.value.confirmDelete ?: return
        _state.update { it.copy(confirmDelete = null) }
        change(
            block = { db ->
                val deletion = when (item) {
                    is Item.Entry -> db.deleteEntry(item.id)
                    is Item.Group -> db.deleteGroup(item.id)
                }
                deletion to true
            },
            onDone = { deletion -> _events.trySend(EditEvent.Deleted(item, deletion)) },
            inForm = false,
        )
    }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = null) }

    fun moveTo(groupId: String) {
        val item = _state.value.move?.item ?: return
        _state.update { it.copy(move = null) }
        change(
            block = { db ->
                val moved = when (item) {
                    is Item.Entry -> db.moveEntry(item.id, groupId)
                    is Item.Group -> db.moveGroup(item.id, groupId)
                }
                moved to moved
            },
            onDone = { moved -> if (moved) _events.trySend(EditEvent.Moved(item)) },
            inForm = false,
        )
    }

    fun cancelMove() = _state.update { it.copy(move = null) }

    /** Saves again, after a failure. */
    fun retrySave() = session.save()

    /** Replaces a file that changed elsewhere with ours. */
    fun overwrite() = session.save(overwrite = true)

    /** Throws our changes away and opens the file as it is now. */
    fun reload() {
        viewModelScope.launch { session.reload() }
    }

    /** Writes the database to a new file the user picked, leaving its own file alone. */
    fun saveCopy(uri: Uri) {
        viewModelScope.launch { _events.send(EditEvent.CopySaved(session.saveCopy(uri))) }
    }

    /**
     * Makes one change in the engine, off the main thread. It runs to the end even if this
     * ViewModel is cleared meanwhile, and then it's saved: a change made in memory must never
     * be left unsaved because a screen went away. [block] returns its result and whether it
     * actually changed anything (an edit that changes nothing needs no save).
     *
     * The engine's refusals ([KeePassException.Invalid], [KeePassException.NotFound]) are shown
     * in the open form, or [inForm] false, as an [EditEvent.Failed]. [wipe] runs afterwards,
     * whatever happens.
     */
    private fun <T> change(
        wipe: () -> Unit = {},
        block: (UnlockedDatabase) -> Pair<T, Boolean>,
        onDone: (T) -> Unit,
        inForm: Boolean = true,
    ) {
        val db = database
        if (db == null || _state.value.busy) {
            wipe()
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val outcome = withContext(NonCancellable) {
                try {
                    Result.success(withContext(Dispatchers.Default) { block(db) })
                } catch (e: KeePassException) {
                    Result.failure(e)
                } catch (e: IllegalStateException) {
                    Result.failure(e) // closed by lock() before the call started
                } finally {
                    wipe()
                }
            }
            _state.update { it.copy(busy = false) }
            outcome.onSuccess { (result, changed) ->
                if (changed) session.changed()
                onDone(result)
            }.onFailure { e ->
                if (e is KeePassException.Locked || e is IllegalStateException) return@onFailure
                val message = e.userMessage(getApplication<Application>().resources)
                if (inForm) {
                    _state.update { it.copy(error = message) }
                } else {
                    _events.trySend(EditEvent.Failed(message))
                }
            }
        }
    }

    /** Asks the engine something off the main thread. Null if the database was locked meanwhile. */
    private suspend fun <T> engine(query: () -> T): T? = withContext(Dispatchers.Default) {
        try {
            query()
        } catch (_: KeePassException) {
            null
        } catch (_: IllegalStateException) {
            null // closed by lock() before the call started
        }
    }

    private fun reset() {
        val current = _state.value
        current.entryForm?.wipe()
        current.groupForm?.wipe()
        current.totpSetup?.input?.wipeText()
        wipeGenerated()
        _state.value = EditState()
    }

    override fun onCleared() {
        val current = _state.value
        current.entryForm?.wipe()
        current.groupForm?.wipe()
        wipeGenerated()
    }
}

/**
 * Where [item] can be moved: every group except the recycle bin and what's in it (deleting
 * puts things there), and for a group, except itself and its own subgroups.
 */
internal fun moveTargets(item: Item, groups: List<GroupChoice>): List<GroupChoice> {
    val out = ArrayList<GroupChoice>(groups.size)
    // While inside the group being moved: its depth. The list is in tree order, so its
    // subgroups are the groups right after it that are deeper.
    var movingDepth: UInt? = null
    for (group in groups) {
        val inside = movingDepth?.let { group.depth > it } ?: false
        if (!inside) movingDepth = null
        when {
            inside -> continue
            item is Item.Group && group.id == item.id -> movingDepth = group.depth
            group.isRecycleBin || group.isInRecycleBin -> continue
            else -> out += group
        }
    }
    return out
}
