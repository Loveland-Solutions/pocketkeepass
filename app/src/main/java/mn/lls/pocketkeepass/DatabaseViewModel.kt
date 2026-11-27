package mn.lls.pocketkeepass

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationResult
import androidx.biometric.BiometricPrompt
import java.io.IOException
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.lls.pocketkeepass.core.DatabaseSummary
import mn.lls.pocketkeepass.core.EntryDetails
import mn.lls.pocketkeepass.core.EntryField
import mn.lls.pocketkeepass.core.FillTarget
import mn.lls.pocketkeepass.core.GroupListing
import mn.lls.pocketkeepass.core.InternalException
import mn.lls.pocketkeepass.core.KeePassException
import mn.lls.pocketkeepass.core.SearchResult
import mn.lls.pocketkeepass.core.UnlockedDatabase
import mn.lls.pocketkeepass.edit.Item
import mn.lls.pocketkeepass.files.Backup
import mn.lls.pocketkeepass.files.Document
import mn.lls.pocketkeepass.files.Documents
import mn.lls.pocketkeepass.files.RecentDatabase
import mn.lls.pocketkeepass.files.fingerprint
import mn.lls.pocketkeepass.security.AutoLockSettings
import mn.lls.pocketkeepass.security.QuickUnlock
import mn.lls.pocketkeepass.security.QuickUnlockSetting
import mn.lls.pocketkeepass.security.decodeUtf8
import mn.lls.pocketkeepass.security.toUtf8Bytes
import mn.lls.pocketkeepass.security.wipe
import mn.lls.pocketkeepass.ui.userMessage

/** Where the app is: no database chosen yet, a database waiting to be unlocked, or open. */
sealed interface DatabaseState {
    data object NoDatabase : DatabaseState

    data class Locked(
        val database: Document,
        val keyFile: Document?,
        /** Whether biometric quick unlock is turned on for this database and usable right now. */
        val quickUnlock: Boolean = false,
        /**
         * Quick unlock is on, but waits for the master password to be typed once: the phone
         * restarted, or it's been [quickUnlockIntervalDays] days. Null otherwise.
         */
        val passwordDueDays: Int? = null,
        val unlocking: Boolean = false,
        val error: UnlockError? = null,
        /**
         * The copy kept from just before the last save, offered when the file is missing or
         * won't open as a database (a save cut short can leave it damaged). Null otherwise.
         */
        val backup: Backup? = null,
        /** Restoring [backup] is running. */
        val restoring: Boolean = false,
        /** The backup was just restored, into the database's file or a new one. */
        val restored: Boolean = false,
    ) : DatabaseState

    data class Unlocked(
        val database: Document,
        val keyFile: Document?,
        val summary: DatabaseSummary,
        /** The group being shown. Starts at the root group. */
        val listing: GroupListing,
        /** Whether the search bar is open. The query itself is [DatabaseViewModel.searchQuery]. */
        val searching: Boolean = false,
        /** Bumped by Ctrl+F ([DatabaseViewModel.find]): the search field takes the cursor again. */
        val searchFocus: Int = 0,
        /** Entries matching the query. Empty while the query is blank. */
        val results: List<SearchResult> = emptyList(),
        /**
         * The entry being shown, on top of the group or search it was opened from. It carries no
         * protected values: those are fetched one at a time, when the user asks.
         */
        val entry: EntryDetails? = null,
        /** Quick unlock's switch in the settings dialog. */
        val quickUnlock: QuickUnlockSetting = QuickUnlockSetting.Unavailable,
        /** How many days quick unlock works before the master password is needed again. */
        val quickUnlockIntervalDays: Int = QuickUnlock.DEFAULT_INTERVAL,
        /**
         * Ask whether to turn quick unlock on: the database was just unlocked with the master
         * password, for the first time since it was picked, on a phone with biometrics set up.
         */
        val offerQuickUnlock: Boolean = false,
    ) : DatabaseState
}

/** An entry's username and password, on their way into another app's login form. */
class Credentials(val username: CharArray, val password: CharArray) {
    fun wipe() {
        username.wipe()
        password.wipe()
    }
}

/** Why an unlock failed. */
sealed interface UnlockError {
    /** The engine refused: wrong key, not a KeePass file, damaged, and so on. Its message is user-facing. */
    data class Engine(val message: String) : UnlockError

    /** The file is gone, or the app lost access to it. Picking it again fixes that. */
    data class CantRead(val keyFile: Boolean) : UnlockError

    /** The biometric prompt failed (too many attempts, for example). The message is Android's own. */
    data class Biometric(val message: String) : UnlockError

    /**
     * Android deleted quick unlock's key, because biometrics or the screen lock changed. Quick
     * unlock has been turned off.
     */
    data object QuickUnlockInvalidated : UnlockError

    /** The stored key no longer opens the database: its master key changed. Quick unlock has been turned off. */
    data object QuickUnlockOutdated : UnlockError

    /** The backup couldn't be written back: the file is gone, or may only be read. */
    data object CantRestore : UnlockError

    /** The backup couldn't be saved as a new file. */
    data object CantSaveBackup : UnlockError
}

/** What a pending biometric prompt is for. */
private sealed interface BiometricPurpose {
    data object Unlock : BiometricPurpose

    /** Turning quick unlock on for this database, which is open. */
    data class Enable(val database: Uri) : BiometricPurpose
}

/**
 * What a screen shows of the database: choosing the file, unlocking it, browsing it, and locking
 * it. The app's own screen and the one autofill shows on top of other apps each have one.
 *
 * The decrypted database itself lives in the app-wide [Session], so every ViewModel sees the
 * same one: unlocking or locking in one place shows everywhere.
 */
/**
 * The "new database" screen, while it's open. Its text (name and master password, twice) lives
 * in [DatabaseViewModel.newName] and friends, out of saved state.
 */
data class NewDatabaseState(
    /** The engine is making the database (timing and running the key derivation). */
    val busy: Boolean = false,
    val error: String? = null,
    /** Made and encrypted, waiting for the user to choose where the file goes. */
    val made: Boolean = false,
    /** The file name to suggest, without ".kdbx". */
    val fileName: String = "",
)

class DatabaseViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val SEARCH_DELAY_MS = 150L
    }

    private val documents = Documents(app)
    private val backups = (app as PocketKeePassApp).backups
    private val recent = RecentDatabase(app)
    private val session = (app as PocketKeePassApp).session

    /** How the file stands with other devices, for the sync line. */
    val sync = session.sync

    /** Changes that came in from other devices, as they arrive. */
    val incoming = session.incoming

    /** Reads the file now for changes made on other devices. */
    fun checkNow() = session.checkNow()
    private val autoLock = (app as PocketKeePassApp).autoLock
    private val clipboard = (app as PocketKeePassApp).clipboard
    private val quickUnlock = QuickUnlock(app)

    private val _biometricPrompts = Channel<AuthenticationRequest>(Channel.CONFLATED)

    /** Biometric prompts for the activity to show. Their results go to [onBiometricResult]. */
    val biometricPrompts: Flow<AuthenticationRequest> = _biometricPrompts.receiveAsFlow()

    /** What the biometric prompt on screen (if any) is for. Kept here so it survives rotation. */
    private var pendingBiometric: BiometricPurpose? = null

    /** Whether the biometric prompt has already been offered since the database was last locked. */
    private var quickUnlockOffered = false

    val autoLockSettings: StateFlow<AutoLockSettings> = autoLock.settings

    /** How long a copied field stays on the clipboard, in seconds. */
    val clipboardTimeout: StateFlow<Int> = clipboard.timeoutSeconds

    /**
     * The master password field's contents. It lives here rather than in `rememberSaveable`
     * state, because saved state is written to disk when Android stops the app.
     */
    val password = TextFieldState()

    /**
     * The search box's contents. Like [password], it stays out of saved state: what someone
     * searches for in their password manager is nobody else's business.
     */
    val searchQuery = TextFieldState()

    /** The new database's name, and its master password typed twice. Out of saved state too. */
    val newName = TextFieldState()
    val newPassword = TextFieldState()
    val newPasswordAgain = TextFieldState()

    private val _newDatabase = MutableStateFlow<NewDatabaseState?>(null)

    /** The "new database" screen's state, or null when it isn't open. */
    val newDatabase: StateFlow<NewDatabaseState?> = _newDatabase.asStateFlow()

    /** The database [newDatabase] made, and its file's bytes, until they're saved or discarded. */
    private var made: Pair<UnlockedDatabase, ByteArray>? = null

    private val database: UnlockedDatabase? get() = session.open.value?.database

    /** The open database [state] was last brought in line with. Null when that was "locked". */
    private var shown: Session.Open? = session.open.value

    /** The group listing being read from the engine, if any. Opening another group cancels it. */
    private var groupJob: Job? = null

    /** The search running in the engine, if any. A newer query cancels it. */
    private var searchJob: Job? = null

    /** The entry being read from the engine, if any. */
    private var entryJob: Job? = null

    private val _state = MutableStateFlow(
        shown?.let(::unlocked) ?: recent.database?.let { locked(it, recent.keyFile) } ?: DatabaseState.NoDatabase,
    )
    val state: StateFlow<DatabaseState> = _state.asStateFlow()

    init {
        // Follow the session: another screen, the autofill service, or auto-lock may unlock or lock it.
        viewModelScope.launch { session.open.collect(::follow) }
        // And re-read what's on screen whenever the database is changed.
        viewModelScope.launch { session.revision.drop(1).collect { refresh() } }
        // Search as the user types, once they pause for a moment.
        viewModelScope.launch {
            snapshotFlow { searchQuery.text.toString() }.collectLatest { query ->
                delay(SEARCH_DELAY_MS)
                search(query)
            }
        }
    }

    /** The user picked a database file. It replaces the previous one, and its key file. */
    fun onDatabasePicked(uri: Uri) {
        session.lock()
        viewModelScope.launch {
            val previous = recent.database
            val previousKeyFile = recent.keyFile
            val document = withContext(Dispatchers.IO) {
                remember(uri, write = true)
                if (previous != null && previous.uri != uri) documents.releaseAccess(previous.uri)
                // Even the same file again: the user may be starting over with different credentials.
                quickUnlock.disable()
                previousKeyFile?.let { documents.releaseAccess(it.uri) }
                Document(uri, documents.displayName(uri))
            }
            recent.database = document
            recent.keyFile = null
            clearPassword()
            _state.value = locked(document, keyFile = null)
        }
    }

    // MARK: A new database

    /** Opens the "new database" screen. */
    fun startNewDatabase() {
        newName.setTextAndPlaceCursorAtEnd(getApplication<Application>().getString(R.string.new_database_default_name))
        clearNewPasswords()
        _newDatabase.value = NewDatabaseState()
    }

    /**
     * Makes the database and its file's bytes, off the main thread, from the name and password
     * typed. The screen then asks where to save it ([newDatabaseSaved]). Slow on purpose: the
     * key derivation is timed, then run again to encrypt the file. Can't be abandoned halfway:
     * a result nobody wants any more is wiped.
     */
    fun createNewDatabase() {
        val current = _newDatabase.value ?: return
        val name = newName.text.toString().trim()
        if (current.busy || current.made || name.isEmpty() || newPassword.text.isEmpty() ||
            newPassword.text.toString() != newPasswordAgain.text.toString()
        ) return
        val passwordBytes = newPassword.text.toUtf8Bytes()
        clearNewPasswords()
        _newDatabase.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default + NonCancellable) {
                try {
                    val database = UnlockedDatabase.create(name, passwordBytes, null)
                    try {
                        Result.success(database to database.save())
                    } catch (e: KeePassException) {
                        database.lock()
                        database.close()
                        Result.failure(e)
                    }
                } catch (e: KeePassException) {
                    Result.failure(e)
                } finally {
                    passwordBytes.wipe()
                }
            }
            val now = _newDatabase.value
            result.onSuccess { pair ->
                if (now == null) {
                    pair.first.lock()
                    pair.first.close()
                    return@launch
                }
                made = pair
                _newDatabase.value = now.copy(busy = false, made = true, fileName = name)
            }.onFailure { e ->
                val resources = getApplication<Application>().resources
                val message = e.userMessage(resources) + " " + resources.getString(R.string.new_database_type_again)
                _newDatabase.value = now?.copy(busy = false, error = message)
            }
        }
    }

    /**
     * The user created the file at [uri] for the new database: writes it (read back and
     * compared), makes it the database, and opens it.
     */
    fun newDatabaseSaved(uri: Uri) {
        val current = _newDatabase.value ?: return
        val (database, bytes) = made ?: return
        _newDatabase.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO + NonCancellable) {
                try {
                    remember(uri, write = true)
                    documents.write(uri, bytes)
                    Triple(Document(uri, documents.displayName(uri)), database.summary(), database.listGroup(null))
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: UnsupportedOperationException) {
                    null
                } catch (_: KeePassException) {
                    null
                }
            }
            if (opened == null) {
                _newDatabase.value = current.copy(
                    busy = false,
                    error = getApplication<Application>().getString(R.string.new_database_cant_save),
                )
                return@launch
            }
            val (document, summary, root) = opened
            made = null
            _newDatabase.value = null
            clearNewPasswords()
            session.lock()
            val previous = recent.database
            val previousKeyFile = recent.keyFile
            withContext(Dispatchers.IO) {
                if (previous != null && previous.uri != uri) documents.releaseAccess(previous.uri)
                previousKeyFile?.let { documents.releaseAccess(it.uri) }
            }
            recent.database = document
            recent.keyFile = null
            // Quick unlock belonged to the previous database. Making this one counts as typing
            // its master password.
            quickUnlock.disable()
            quickUnlock.passwordTyped(uri)
            val open = Session.Open(database, document, null, summary, root, fingerprint(bytes))
            session.start(open)
            follow(open)
            if (quickUnlock.shouldOffer(uri)) {
                _state.update { (it as? DatabaseState.Unlocked)?.copy(offerQuickUnlock = true) ?: it }
            }
        }
    }

    /** Leaves the "new database" screen: anything made is wiped, and the passwords cleared. */
    fun cancelNewDatabase() {
        _newDatabase.value = null
        clearNewPasswords()
        made?.let { (database, bytes) ->
            made = null
            bytes.wipe()
            viewModelScope.launch(Dispatchers.Default) {
                database.lock()
                database.close()
            }
        }
    }

    /** Empties both new password fields, and their undo history. */
    @OptIn(ExperimentalFoundationApi::class) // undoState
    private fun clearNewPasswords() {
        for (field in listOf(newPassword, newPasswordAgain)) {
            field.clearText()
            field.undoState.clearHistory()
        }
    }

    /**
     * Writes the backup over the database's file, which is missing or damaged. The user then
     * unlocks it as usual.
     */
    fun restoreBackup() {
        val locked = _state.value as? DatabaseState.Locked ?: return
        if (locked.backup == null || locked.unlocking || locked.restoring) return
        _state.value = locked.copy(restoring = true, error = null)
        viewModelScope.launch {
            val uri = locked.database.uri
            val backup = locked.backup
            val ok = withContext(Dispatchers.IO + NonCancellable) {
                writeBackup(backup, from = uri, to = uri).also {
                    // The file now holds the unfinished save; the previous version stays.
                    if (it && backup.unfinished) backups.finished(uri.toString())
                }
            }
            if (!stillShowing(locked)) return@launch
            _state.value = if (ok) {
                locked.copy(error = null, backup = null, restored = true)
            } else {
                locked.copy(error = UnlockError.CantRestore)
            }
        }
    }

    /**
     * Writes the backup into a new file the user just created ([uri]), and makes that the
     * database, with the same key file. For when the old file is gone or can't be written.
     */
    fun saveBackupAs(uri: Uri) {
        val locked = _state.value as? DatabaseState.Locked ?: return
        if (locked.backup == null || locked.unlocking || locked.restoring) return
        _state.value = locked.copy(restoring = true, error = null)
        viewModelScope.launch {
            val old = locked.database.uri
            val document = withContext(Dispatchers.IO + NonCancellable) {
                remember(uri, write = true)
                if (!writeBackup(locked.backup, from = old, to = uri)) {
                    documents.releaseAccess(uri)
                    return@withContext null
                }
                try {
                    backups.move(old.toString(), uri.toString())
                } catch (_: IOException) {
                    // The new file is fine; its first save makes a backup of its own.
                }
                documents.releaseAccess(old)
                Document(uri, documents.displayName(uri))
            }
            if (!stillShowing(locked)) return@launch
            if (document == null) {
                _state.value = locked.copy(error = UnlockError.CantSaveBackup)
                return@launch
            }
            recent.database = document
            // Quick unlock belongs to a database's location.
            quickUnlock.disable()
            _state.value = locked(document, locked.keyFile).copy(restored = true)
        }
    }

    /** Whether the unlock screen for [locked]'s database is still showing, now restoring its backup. */
    private fun stillShowing(locked: DatabaseState.Locked): Boolean =
        (_state.value as? DatabaseState.Locked)?.let { it.restoring && it.database == locked.database } == true

    /** Copies [backup], of the database at [from], into the file at [to]. Returns whether it worked. */
    private fun writeBackup(backup: Backup, from: Uri, to: Uri): Boolean = try {
        documents.write(to, backups.read(from.toString(), backup))
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    fun onKeyFilePicked(uri: Uri) {
        val locked = _state.value as? DatabaseState.Locked ?: return
        viewModelScope.launch {
            val previous = recent.keyFile
            val document = withContext(Dispatchers.IO) {
                remember(uri, write = false)
                if (previous != null && previous.uri != uri) documents.releaseAccess(previous.uri)
                Document(uri, documents.displayName(uri))
            }
            recent.keyFile = document
            // The stored key was made without this key file; it would open the database anyway, confusingly.
            quickUnlock.disable()
            _state.value = locked.copy(keyFile = document, quickUnlock = false, error = null, backup = null)
        }
    }

    fun removeKeyFile() {
        val locked = _state.value as? DatabaseState.Locked ?: return
        val keyFile = locked.keyFile ?: return
        recent.keyFile = null
        viewModelScope.launch(Dispatchers.IO) { documents.releaseAccess(keyFile.uri) }
        quickUnlock.disable()
        _state.value = locked.copy(keyFile = null, quickUnlock = false, error = null, backup = null)
    }

    fun unlock() {
        val locked = _state.value as? DatabaseState.Locked ?: return
        if (locked.unlocking || locked.restoring) return

        // An empty password with a key file means "key file only". An empty password on its
        // own is a real (if unwise) password: KeePass allows it.
        val passwordBytes = if (password.text.isEmpty() && locked.keyFile != null) null else password.text.toUtf8Bytes()

        unlockWith(locked, viaQuickUnlock = false, onDone = { passwordBytes?.wipe() }) { file ->
            val key = locked.keyFile?.let { read(it.uri, isKeyFile = true) }
            try {
                withContext(Dispatchers.Default) { UnlockedDatabase.open(file, passwordBytes, key) }
            } finally {
                key?.wipe()
            }
        }
    }

    /**
     * Shows the biometric prompt once after each lock, if quick unlock is on. The unlock screen
     * calls this when it appears; the button calls [quickUnlock] instead, which always asks.
     */
    fun offerQuickUnlock() {
        if (quickUnlockOffered) return
        quickUnlockOffered = true
        quickUnlock()
    }

    /** Asks for the user's fingerprint (or face) to unlock the database. */
    fun quickUnlock() {
        val locked = _state.value as? DatabaseState.Locked ?: return
        if (locked.unlocking || locked.restoring || !locked.quickUnlock) return
        if (quickUnlock.isPasswordDue(locked.database.uri)) {
            _state.value = locked(locked.database, locked.keyFile).copy(error = locked.error, backup = locked.backup)
            return
        }
        val cipher = quickUnlock.decryptingCipher(locked.database.uri)
        if (cipher == null) {
            _state.value = locked.copy(quickUnlock = false, error = UnlockError.QuickUnlockInvalidated)
            return
        }
        prompt(BiometricPurpose.Unlock, cipher, R.string.biometric_unlock_title, R.string.use_master_password, subtitle = locked.database.name)
    }

    /** Turns quick unlock on (with a biometric check) or off for the open database. */
    fun setQuickUnlock(on: Boolean) {
        val unlocked = _state.value as? DatabaseState.Unlocked ?: return
        if (!on) {
            quickUnlock.disable()
            refreshQuickUnlock()
            return
        }
        if (unlocked.quickUnlock != QuickUnlockSetting.Off) return
        val cipher = try {
            quickUnlock.encryptingCipher()
        } catch (_: GeneralSecurityException) {
            refreshQuickUnlock() // the Keystore refused; the switch stays off
            return
        }
        prompt(BiometricPurpose.Enable(unlocked.database.uri), cipher, R.string.biometric_enable_title, R.string.cancel)
    }

    /** Sets how many days quick unlock works before the master password is needed again. */
    fun setQuickUnlockInterval(days: Int) {
        if (days !in QuickUnlock.INTERVALS) return
        quickUnlock.intervalDays = days
        autoLock.onUserInteraction() // taps in a dialog don't reach the activity
        _state.update { (it as? DatabaseState.Unlocked)?.copy(quickUnlockIntervalDays = days) ?: it }
    }

    /** The answer to [DatabaseState.Unlocked.offerQuickUnlock]. Either way, it isn't asked again for this database. */
    fun answerQuickUnlockOffer(turnOn: Boolean) {
        val unlocked = _state.value as? DatabaseState.Unlocked ?: return
        if (!unlocked.offerQuickUnlock) return
        quickUnlock.offered(unlocked.database.uri)
        _state.value = unlocked.copy(offerQuickUnlock = false)
        if (turnOn) setQuickUnlock(true)
    }

    /**
     * Re-checks what may have changed while this screen was away: whether quick unlock can be
     * used (biometrics may have been set up or removed), and, on the unlock screen, which
     * database is chosen (another screen may have picked a different one).
     */
    fun refreshQuickUnlock() {
        val shownNow = _state.value
        if (shownNow !is DatabaseState.Unlocked && !(shownNow is DatabaseState.Locked && shownNow.unlocking)) {
            val database = recent.database
            val keyFile = recent.keyFile
            val locked = shownNow as? DatabaseState.Locked
            if (database != locked?.database || keyFile != locked?.keyFile) {
                _state.value = database?.let { locked(it, keyFile) } ?: DatabaseState.NoDatabase
                return
            }
        }
        _state.update { current ->
            when (current) {
                is DatabaseState.Locked -> {
                    val fresh = locked(current.database, current.keyFile)
                    current.copy(quickUnlock = fresh.quickUnlock, passwordDueDays = fresh.passwordDueDays)
                }
                is DatabaseState.Unlocked -> current.copy(quickUnlock = quickUnlock.setting(current.database.uri))
                DatabaseState.NoDatabase -> current
            }
        }
    }

    /** The biometric prompt's answer, from the activity. */
    fun onBiometricResult(result: AuthenticationResult) {
        val purpose = pendingBiometric ?: return // from before the process restarted
        pendingBiometric = null
        if (!result.isSuccess()) {
            val error = result.error() // null if the user chose the master password (or "Cancel") instead
            // Cancelling needs no message either.
            if (purpose == BiometricPurpose.Unlock && error != null && error.errorCode !in QUIET_BIOMETRIC_ERRORS) {
                _state.update { (it as? DatabaseState.Locked)?.copy(error = UnlockError.Biometric(error.errString.toString())) ?: it }
            }
            refreshQuickUnlock()
            return
        }
        val cipher = result.success()?.crypto?.cipher ?: return
        when (purpose) {
            BiometricPurpose.Unlock -> unlockWithKeyHash(cipher)
            is BiometricPurpose.Enable -> enableQuickUnlock(purpose.database, cipher)
        }
    }

    private fun prompt(purpose: BiometricPurpose, cipher: Cipher, title: Int, cancel: Int, subtitle: String? = null) {
        val app = getApplication<Application>()
        pendingBiometric = purpose
        _biometricPrompts.trySend(
            AuthenticationRequest.biometricRequest(
                app.getString(title),
                AuthenticationRequest.Biometric.Fallback.CustomOption(
                    app.getString(cancel),
                    if (purpose == BiometricPurpose.Unlock) {
                        AuthenticationRequest.Biometric.Fallback.ICON_TYPE_PASSWORD
                    } else {
                        AuthenticationRequest.Biometric.Fallback.ICON_TYPE_GENERIC
                    },
                ),
            ) {
                setMinStrength(AuthenticationRequest.Biometric.Strength.Class3(BiometricPrompt.CryptoObject(cipher)))
                subtitle?.let(::setSubtitle)
            },
        )
    }

    private fun unlockWithKeyHash(cipher: Cipher) {
        val locked = _state.value as? DatabaseState.Locked ?: return
        if (locked.unlocking || locked.restoring) return
        unlockWith(locked, viaQuickUnlock = true) { file ->
            withContext(Dispatchers.Default) {
                val hash = try {
                    quickUnlock.keyHash(cipher)
                } catch (e: GeneralSecurityException) {
                    throw QuickUnlockBrokenException(e)
                }
                try {
                    UnlockedDatabase.openWithKeyHash(file, hash)
                } finally {
                    hash.wipe()
                }
            }
        }
    }

    private fun enableQuickUnlock(database: Uri, cipher: Cipher) {
        val db = this.database ?: return // locked while the prompt was up
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val hash = try {
                    db.keyHash()
                } catch (_: KeePassException) {
                    return@withContext // locked meanwhile
                } catch (_: IllegalStateException) {
                    return@withContext // closed by lock() before the call started
                }
                try {
                    quickUnlock.store(database, cipher, hash)
                } catch (_: GeneralSecurityException) {
                    quickUnlock.disable()
                } finally {
                    hash.wipe()
                }
            }
            refreshQuickUnlock()
        }
    }

    /**
     * Opens the database with [open], which gets the database file's bytes and runs on a
     * background thread. [onDone] runs afterwards, success or not, to wipe the caller's secrets.
     */
    private fun unlockWith(
        locked: DatabaseState.Locked,
        viaQuickUnlock: Boolean,
        onDone: () -> Unit = {},
        open: suspend (ByteArray) -> UnlockedDatabase,
    ) {
        _state.value = locked.copy(unlocking = true, error = null, restored = false)
        viewModelScope.launch {
            val outcome = try {
                Result.success(open(locked.database, open))
            } catch (e: KeePassException) {
                Result.failure(e)
            } catch (e: CantReadException) {
                Result.failure(e)
            } catch (e: QuickUnlockBrokenException) {
                Result.failure(e)
            } finally {
                onDone()
            }

            val (opened, unlocked) = outcome.getOrElse { e ->
                val error = when {
                    e is CantReadException -> UnlockError.CantRead(e.keyFile)
                    e is QuickUnlockBrokenException -> UnlockError.QuickUnlockInvalidated
                    viaQuickUnlock && e is KeePassException.WrongKey -> UnlockError.QuickUnlockOutdated
                    else -> UnlockError.Engine(e.userMessage(getApplication<Application>().resources))
                }
                if (error is UnlockError.QuickUnlockInvalidated || error is UnlockError.QuickUnlockOutdated) quickUnlock.disable()
                if (e is KeePassException.WrongKey && !viaQuickUnlock) clearPassword()
                // Not UnsupportedVersion: that's a good file in a newer format (another app
                // upgraded it), and restoring an older copy over it would throw its content away.
                val damaged = e is KeePassException.NotKeePass || e is KeePassException.Corrupt ||
                    (e is CantReadException && !e.keyFile)
                val backup = if (damaged) withContext(Dispatchers.IO) { backups.find(locked.database.uri.toString()) } else null
                val fresh = locked(locked.database, locked.keyFile)
                _state.value = locked.copy(
                    unlocking = false,
                    error = error,
                    quickUnlock = fresh.quickUnlock,
                    passwordDueDays = fresh.passwordDueDays,
                    backup = backup,
                    restored = false,
                )
                return@launch
            }
            if (!isActive) {
                // The ViewModel was cleared while the key was being derived. Nobody will use this.
                opened.lock()
                opened.close()
                return@launch
            }
            if (!viaQuickUnlock) quickUnlock.passwordTyped(locked.database.uri)
            val open = Session.Open(opened, locked.database, locked.keyFile, unlocked.summary, unlocked.root, unlocked.fingerprint)
            session.start(open)
            follow(open)
            // Only the app's own screen asks. Autofill's and passkeys' screens ignore this, so
            // after unlocking there the question waits for the next unlock in the app.
            if (!viaQuickUnlock && quickUnlock.shouldOffer(locked.database.uri)) {
                _state.update { (it as? DatabaseState.Unlocked)?.copy(offerQuickUnlock = true) ?: it }
            }
        }
    }

    /**
     * Brings [state] in line with the session: unlocked at the root group when a database was
     * just opened (here or elsewhere), back to the unlock screen when it was locked.
     */
    private fun follow(open: Session.Open?) {
        if (open === shown) return
        shown = open
        groupJob?.cancel()
        searchJob?.cancel()
        entryJob?.cancel()
        searchQuery.clearText()
        if (open != null) {
            clearPassword()
            _state.value = unlocked(open)
        } else {
            quickUnlockOffered = false
            val unlocked = _state.value as? DatabaseState.Unlocked ?: return
            _state.value = locked(unlocked.database, unlocked.keyFile)
        }
    }

    private fun unlocked(open: Session.Open) = DatabaseState.Unlocked(
        open.document,
        open.keyFile,
        open.summary,
        open.root,
        quickUnlock = quickUnlock.setting(open.document.uri),
        quickUnlockIntervalDays = quickUnlock.intervalDays,
    )

    /** Shows a group's contents. `id` comes from a [GroupListing]; null means the root group. */
    fun openGroup(id: String?) {
        if (_state.value !is DatabaseState.Unlocked) return
        groupJob?.cancel()
        groupJob = read(
            query = { db ->
                try {
                    db.listGroup(id)
                } catch (_: KeePassException.NotFound) {
                    db.listGroup(null) // deleted meanwhile: go home
                }
            },
            apply = { listing -> _state.update { (it as? DatabaseState.Unlocked)?.copy(listing = listing) ?: it } },
        )
    }

    /** Goes to the parent group. Returns false at the root group, where there's nowhere to go. */
    fun goUp(): Boolean {
        val unlocked = _state.value as? DatabaseState.Unlocked ?: return false
        val parent = unlocked.listing.parents.lastOrNull() ?: return false
        openGroup(parent.id)
        return true
    }

    fun openSearch() {
        _state.update { (it as? DatabaseState.Unlocked)?.copy(searching = true) ?: it }
    }

    /**
     * Ctrl+F on a keyboard: from a group or an entry, opens search (closing the entry) with the
     * cursor in its field. Returns false when no database is open.
     */
    fun find(): Boolean {
        if (_state.value !is DatabaseState.Unlocked) return false
        entryJob?.cancel()
        _state.update {
            (it as? DatabaseState.Unlocked)?.copy(searching = true, entry = null, searchFocus = it.searchFocus + 1) ?: it
        }
        return true
    }

    fun closeSearch() {
        searchQuery.clearText()
        _state.update { (it as? DatabaseState.Unlocked)?.copy(searching = false, results = emptyList()) ?: it }
    }

    private fun search(query: String) {
        val unlocked = _state.value as? DatabaseState.Unlocked ?: return
        if (!unlocked.searching) return
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { (it as? DatabaseState.Unlocked)?.copy(results = emptyList()) ?: it }
            return
        }
        searchJob = read(
            query = { db -> db.search(query) },
            apply = { results ->
                _state.update { current ->
                    // The user may have closed the search while it ran.
                    if (current is DatabaseState.Unlocked && current.searching) current.copy(results = results) else current
                }
            },
        )
    }

    /** Shows an entry. `id` comes from a listing or a search result. */
    fun openEntry(id: String) {
        if (_state.value !is DatabaseState.Unlocked) return
        entryJob?.cancel()
        entryJob = read(
            query = { db ->
                try {
                    db.entry(id)
                } catch (_: KeePassException.NotFound) {
                    null // deleted meanwhile
                }
            },
            apply = { entry -> _state.update { (it as? DatabaseState.Unlocked)?.copy(entry = entry) ?: it } },
        )
    }

    /**
     * Re-reads everything on screen after the database was changed (here or by the editor):
     * the summary, the group, the search results, and the entry. Whatever was deleted goes
     * away: an entry closes, and a group falls back to the root group.
     */
    private fun refresh() {
        val unlocked = _state.value as? DatabaseState.Unlocked ?: return
        val groupId = unlocked.listing.group.id
        val entryId = unlocked.entry?.id
        val query = if (unlocked.searching) searchQuery.text.toString() else null
        read(
            query = { db ->
                val summary = db.summary()
                val listing = try {
                    db.listGroup(groupId)
                } catch (_: KeePassException.NotFound) {
                    db.listGroup(null)
                }
                val entry = entryId?.let {
                    try {
                        db.entry(it)
                    } catch (_: KeePassException.NotFound) {
                        null
                    }
                }
                val results = query?.takeIf { it.isNotBlank() }?.let(db::search).orEmpty()
                Refreshed(summary, listing, entry, results)
            },
            apply = { r ->
                _state.update { current ->
                    if (current !is DatabaseState.Unlocked) return@update current
                    current.copy(
                        summary = r.summary,
                        listing = r.listing,
                        // Only the entry that was asked about: the user may have moved on.
                        entry = if (current.entry?.id == entryId) r.entry else current.entry,
                        results = if (current.searching) r.results else current.results,
                    )
                }
            },
        )
    }

    private class Refreshed(
        val summary: DatabaseSummary,
        val listing: GroupListing,
        val entry: EntryDetails?,
        val results: List<SearchResult>,
    )

    /**
     * Leaves something that was just deleted: closes it if it's the entry being shown, or goes
     * up a level if it's the group being shown.
     */
    fun leave(item: Item) {
        val unlocked = _state.value as? DatabaseState.Unlocked ?: return
        when (item) {
            is Item.Entry -> if (unlocked.entry?.id == item.id) closeEntry()
            is Item.Group -> if (unlocked.listing.group.id == item.id) goUp()
        }
    }

    /** Back from the entry to the group or search it was opened from. */
    fun closeEntry() {
        entryJob?.cancel()
        _state.update { (it as? DatabaseState.Unlocked)?.copy(entry = null) ?: it }
    }

    /**
     * Fetches a field's value for showing it on screen, or null if the database was locked
     * meanwhile. Compose can only draw a `String`, which can't be wiped, so call this only when
     * the user asks to see the value.
     */
    suspend fun reveal(entryId: String, field: EntryField): String? {
        val chars = revealChars(entryId, field) ?: return null
        return try {
            String(chars)
        } finally {
            chars.wipe()
        }
    }

    /**
     * Copies a field to the clipboard. Protected fields are marked sensitive. Our own copy is
     * wiped straight after, but Android briefly turns the text into a `String` to hand it to the
     * clipboard service, and that copy can't be wiped. It isn't kept anywhere, at least.
     */
    fun copy(entryId: String, field: EntryField, label: String) {
        viewModelScope.launch {
            val chars = revealChars(entryId, field) ?: return@launch
            try {
                clipboard.copy(label, CharBuffer.wrap(chars), sensitive = field.isProtected)
            } finally {
                chars.wipe()
            }
        }
    }

    fun setClipboardTimeout(seconds: Int) = clipboard.setTimeout(seconds)

    /**
     * The entries linked to the app or web page autofill is filling in, or null if the database
     * was locked meanwhile. Like every list, they carry no protected values.
     */
    suspend fun autofillMatches(target: FillTarget): List<SearchResult>? {
        val db = database ?: return null
        return withContext(Dispatchers.Default) {
            try {
                db.autofillMatches(target)
            } catch (_: KeePassException.Locked) {
                null
            } catch (_: IllegalStateException) {
                null // closed by lock() before the call started
            }
        }
    }

    /**
     * An entry's username and password for autofill to fill in, or null if the database was
     * locked meanwhile or the entry is gone. The caller wipes them ([Credentials.wipe]).
     */
    suspend fun credentials(entryId: String): Credentials? {
        val username = revealChars(entryId, "UserName") ?: return null
        val password = revealChars(entryId, "Password")
        if (password == null) {
            username.wipe()
            return null
        }
        autoLock.onUserInteraction() // filling in a password is using the app
        return Credentials(username, password)
    }

    private suspend fun revealChars(entryId: String, field: EntryField): CharArray? = revealChars(entryId, field.key)

    private suspend fun revealChars(entryId: String, key: String): CharArray? = engineChars { it.revealField(entryId, key) }

    /**
     * The entry's TOTP code for [unixSeconds] (seconds since 1970, UTC), for showing it, or null
     * if the database was locked meanwhile, the entry is gone, or its settings can't be used.
     * Like a revealed field, it's a `String` only because Compose needs one, so call this only
     * while the user has asked to see the code.
     */
    suspend fun totpCode(entryId: String, unixSeconds: Long): String? {
        val chars = totpChars(entryId, unixSeconds) ?: return null
        return try {
            String(chars)
        } finally {
            chars.wipe()
        }
    }

    /** Copies the entry's current TOTP code, marked sensitive like a password. */
    fun copyTotp(entryId: String, label: String) {
        viewModelScope.launch {
            val chars = totpChars(entryId, System.currentTimeMillis() / 1000) ?: return@launch
            try {
                clipboard.copy(label, CharBuffer.wrap(chars), sensitive = true)
            } finally {
                chars.wipe()
            }
        }
    }

    private suspend fun totpChars(entryId: String, unixSeconds: Long): CharArray? =
        engineChars { it.totpCode(entryId, unixSeconds.toULong()) }

    /** Asks the engine for secret bytes off the main thread, and turns them into a wipeable `CharArray`. */
    private suspend fun engineChars(call: (UnlockedDatabase) -> ByteArray): CharArray? {
        val db = database ?: return null
        return withContext(Dispatchers.Default) {
            val bytes = try {
                call(db)
            } catch (_: KeePassException) {
                return@withContext null // locked meanwhile, gone, or (TOTP) unusable settings
            } catch (_: IllegalStateException) {
                return@withContext null // closed by lock() before the call started
            } catch (_: InternalException) {
                return@withContext null // a bug in the engine: nothing to copy, but no crash
            }
            try {
                bytes.decodeUtf8()
            } finally {
                bytes.wipe()
            }
        }
    }

    /**
     * Asks the open database something off the main thread, then hands the answer to [apply] on
     * the main thread. A job cancelled meanwhile never gets to [apply], even if the engine had
     * already answered, so a slow, outdated answer can't overwrite a newer one. If the database
     * is locked meanwhile, the engine refuses to answer and nothing happens.
     */
    private fun <T> read(query: (UnlockedDatabase) -> T, apply: (T) -> Unit): Job? {
        val db = database ?: return null
        return viewModelScope.launch {
            val answer = try {
                withContext(Dispatchers.Default) { query(db) }
            } catch (_: KeePassException.Locked) {
                return@launch
            } catch (_: IllegalStateException) {
                return@launch // closed by lock() before the call started
            } catch (_: InternalException) {
                return@launch // a bug in the engine: no answer, but no crash either
            }
            apply(answer)
        }
    }

    fun setAutoLock(settings: AutoLockSettings) = autoLock.update(settings)

    /** Wipes the decrypted database and goes back to the unlock screen. */
    fun lock() {
        session.lock()
        follow(null)
    }

    // The database stays open in the session: this may be the screen autofill showed, closing
    // after its job is done. The app's own screen locks when it's closed for good (MainActivity).
    override fun onCleared() {
        clearPassword()
        searchQuery.clearText()
        clearNewPasswords()
        made?.let { (database, bytes) ->
            made = null
            bytes.wipe()
            database.lock()
            database.close()
        }
    }

    /**
     * Reads the database file and hands it to [unlock], which derives the key. That takes
     * seconds on purpose, and it runs to the end even if the caller is cancelled, so the result
     * can always be wiped rather than abandoned.
     */
    private suspend fun open(
        database: Document,
        unlock: suspend (ByteArray) -> UnlockedDatabase,
    ): Pair<UnlockedDatabase, Opened> = withContext(Dispatchers.IO + NonCancellable) {
        val file = read(database.uri, isKeyFile = false)
        val opened = unlock(file)
        try {
            withContext(Dispatchers.Default) { opened to Opened(opened.summary(), opened.listGroup(null), fingerprint(file)) }
        } catch (e: KeePassException) {
            opened.lock()
            opened.close()
            throw e
        }
    }

    /** The unlock screen for [database], with quick unlock offered if it's on, usable, and not waiting for the password. */
    private fun locked(database: Document, keyFile: Document?): DatabaseState.Locked {
        val on = quickUnlock.isAvailable && quickUnlock.isEnabledFor(database.uri)
        val due = on && quickUnlock.isPasswordDue(database.uri)
        return DatabaseState.Locked(
            database,
            keyFile,
            quickUnlock = on && !due,
            passwordDueDays = if (due) quickUnlock.intervalDays else null,
        )
    }

    private fun read(uri: Uri, isKeyFile: Boolean): ByteArray = try {
        documents.read(uri)
    } catch (e: IOException) {
        throw CantReadException(isKeyFile, e)
    } catch (e: SecurityException) {
        throw CantReadException(isKeyFile, e)
    } catch (e: IllegalArgumentException) {
        throw CantReadException(isKeyFile, e)
    }

    /**
     * Keeps access to a picked file across restarts. If the provider doesn't allow that, the
     * file still works until the app restarts, and the unlock screen asks for it again then.
     */
    private fun remember(uri: Uri, write: Boolean) {
        try {
            documents.keepAccess(uri, write)
        } catch (_: SecurityException) {
        }
    }

    /**
     * The app left the screen. A master password typed but not used (the file couldn't be read,
     * or the user simply walked away) is cleared, so nobody who picks up the phone later can
     * reveal it with "show password".
     */
    fun onAppHidden() {
        val locked = _state.value as? DatabaseState.Locked ?: return
        if (!locked.unlocking) clearPassword()
    }

    /** Empties the password field, and its undo history, which holds earlier copies of the text. */
    @OptIn(ExperimentalFoundationApi::class) // undoState
    private fun clearPassword() {
        password.clearText()
        password.undoState.clearHistory()
    }

    /** What the app shows straight after unlocking. */
    private class Opened(val summary: DatabaseSummary, val root: GroupListing, val fingerprint: ByteArray)

    private class CantReadException(val keyFile: Boolean, cause: Throwable) : Exception(cause)

    /** Quick unlock's stored data couldn't be decrypted. */
    private class QuickUnlockBrokenException(cause: Throwable) : Exception(cause)
}

/**
 * Biometric errors that need no message: the user cancelled, chose the master password, or the
 * app or the system closed the prompt (`BiometricPrompt.ERROR_*`).
 */
private val QUIET_BIOMETRIC_ERRORS = setOf(
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
)
