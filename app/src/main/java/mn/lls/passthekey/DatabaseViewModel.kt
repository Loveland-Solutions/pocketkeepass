package mn.lls.passthekey

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.nio.CharBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.lls.passthekey.core.DatabaseSummary
import mn.lls.passthekey.core.EntryDetails
import mn.lls.passthekey.core.EntryField
import mn.lls.passthekey.core.GroupListing
import mn.lls.passthekey.core.KeePassException
import mn.lls.passthekey.core.SearchResult
import mn.lls.passthekey.core.UnlockedDatabase
import mn.lls.passthekey.files.Document
import mn.lls.passthekey.files.Documents
import mn.lls.passthekey.files.RecentDatabase
import mn.lls.passthekey.security.AutoLockSettings
import mn.lls.passthekey.security.decodeUtf8
import mn.lls.passthekey.security.toUtf8Bytes
import mn.lls.passthekey.security.wipe

/** Where the app is: no database chosen yet, a database waiting to be unlocked, or open. */
sealed interface DatabaseState {
    data object NoDatabase : DatabaseState

    data class Locked(
        val database: Document,
        val keyFile: Document?,
        val unlocking: Boolean = false,
        val error: UnlockError? = null,
    ) : DatabaseState

    data class Unlocked(
        val database: Document,
        val keyFile: Document?,
        val summary: DatabaseSummary,
        /** The group being shown. Starts at the root group. */
        val listing: GroupListing,
        /** Whether the search bar is open. The query itself is [DatabaseViewModel.searchQuery]. */
        val searching: Boolean = false,
        /** Entries matching the query. Empty while the query is blank. */
        val results: List<SearchResult> = emptyList(),
        /**
         * The entry being shown, on top of the group or search it was opened from. It carries no
         * protected values: those are fetched one at a time, when the user asks.
         */
        val entry: EntryDetails? = null,
    ) : DatabaseState
}

/** Why an unlock failed. */
sealed interface UnlockError {
    /** The engine refused: wrong key, not a KeePass file, damaged, and so on. Its message is user-facing. */
    data class Engine(val message: String) : UnlockError

    /** The file is gone, or the app lost access to it. Picking it again fixes that. */
    data class CantRead(val keyFile: Boolean) : UnlockError
}

/**
 * Owns the open database for the whole app: choosing the file, unlocking it, and locking it.
 *
 * The decrypted database lives only here. It's locked (wiped) when the user locks it, when
 * auto-lock says so (timeout, screen off), and when this ViewModel is cleared, which happens
 * when the activity finishes for good.
 */
class DatabaseViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val SEARCH_DELAY_MS = 150L
    }

    private val documents = Documents(app)
    private val recent = RecentDatabase(app)
    private val autoLock = (app as PassTheKeyApp).autoLock
    private val clipboard = (app as PassTheKeyApp).clipboard

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

    private var database: UnlockedDatabase? = null

    /** The group listing being read from the engine, if any. Opening another group cancels it. */
    private var groupJob: Job? = null

    /** The search running in the engine, if any. A newer query cancels it. */
    private var searchJob: Job? = null

    /** The entry being read from the engine, if any. */
    private var entryJob: Job? = null

    private val _state = MutableStateFlow(
        recent.database?.let { DatabaseState.Locked(it, recent.keyFile) } ?: DatabaseState.NoDatabase,
    )
    val state: StateFlow<DatabaseState> = _state.asStateFlow()

    init {
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
        viewModelScope.launch {
            val previous = recent.database
            val previousKeyFile = recent.keyFile
            val document = withContext(Dispatchers.IO) {
                remember(uri, write = true)
                if (previous != null && previous.uri != uri) documents.releaseAccess(previous.uri)
                previousKeyFile?.let { documents.releaseAccess(it.uri) }
                Document(uri, documents.displayName(uri))
            }
            recent.database = document
            recent.keyFile = null
            clearPassword()
            _state.value = DatabaseState.Locked(document, keyFile = null)
        }
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
            _state.value = locked.copy(keyFile = document, error = null)
        }
    }

    fun removeKeyFile() {
        val locked = _state.value as? DatabaseState.Locked ?: return
        val keyFile = locked.keyFile ?: return
        recent.keyFile = null
        viewModelScope.launch(Dispatchers.IO) { documents.releaseAccess(keyFile.uri) }
        _state.value = locked.copy(keyFile = null, error = null)
    }

    fun unlock() {
        val locked = _state.value as? DatabaseState.Locked ?: return
        if (locked.unlocking) return
        _state.value = locked.copy(unlocking = true, error = null)

        // An empty password with a key file means "key file only". An empty password on its
        // own is a real (if unwise) password: KeePass allows it.
        val passwordBytes = if (password.text.isEmpty() && locked.keyFile != null) null else password.text.toUtf8Bytes()

        viewModelScope.launch {
            val outcome = try {
                Result.success(open(locked.database, locked.keyFile, passwordBytes))
            } catch (e: KeePassException) {
                Result.failure(e)
            } catch (e: CantReadException) {
                Result.failure(e)
            } finally {
                passwordBytes?.wipe()
            }

            val (opened, unlocked) = outcome.getOrElse { e ->
                if (e is KeePassException.WrongKey) clearPassword()
                val error = when (e) {
                    is CantReadException -> UnlockError.CantRead(e.keyFile)
                    else -> UnlockError.Engine(e.message.orEmpty())
                }
                _state.value = locked.copy(unlocking = false, error = error)
                return@launch
            }
            if (!isActive) {
                // The ViewModel was cleared while the key was being derived. Nobody will use this.
                opened.lock()
                opened.close()
                return@launch
            }
            database = opened
            autoLock.arm(::lock)
            clearPassword()
            _state.value = DatabaseState.Unlocked(locked.database, locked.keyFile, unlocked.summary, unlocked.root)
        }
    }

    /** Shows a group's contents. `id` comes from a [GroupListing]; null means the root group. */
    fun openGroup(id: String?) {
        if (_state.value !is DatabaseState.Unlocked) return
        groupJob?.cancel()
        groupJob = read(
            query = { db ->
                try {
                    db.listGroup(id)
                } catch (_: KeePassException.NotFound) {
                    db.listGroup(null) // gone, which can't happen until editing exists: go home
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
                    null // can't happen until editing exists
                }
            },
            apply = { entry -> _state.update { (it as? DatabaseState.Unlocked)?.copy(entry = entry) ?: it } },
        )
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

    private suspend fun revealChars(entryId: String, field: EntryField): CharArray? {
        val db = database ?: return null
        return withContext(Dispatchers.Default) {
            val bytes = try {
                db.revealField(entryId, field.key)
            } catch (_: KeePassException) {
                return@withContext null // locked meanwhile, or (once editing exists) gone
            } catch (_: IllegalStateException) {
                return@withContext null // closed by lock() before the call started
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
            }
            apply(answer)
        }
    }

    fun setAutoLock(settings: AutoLockSettings) = autoLock.update(settings)

    /** Wipes the decrypted database and goes back to the unlock screen. */
    fun lock() {
        closeDatabase()
        searchQuery.clearText()
        val unlocked = _state.value as? DatabaseState.Unlocked ?: return
        _state.value = DatabaseState.Locked(unlocked.database, unlocked.keyFile)
    }

    override fun onCleared() {
        closeDatabase()
        clearPassword()
        searchQuery.clearText()
    }

    /**
     * Reads the files and derives the key. That takes seconds on purpose, and it runs to the end
     * even if the caller is cancelled, so the result can always be wiped rather than abandoned.
     */
    private suspend fun open(
        database: Document,
        keyFile: Document?,
        password: ByteArray?,
    ): Pair<UnlockedDatabase, Opened> = withContext(Dispatchers.IO + NonCancellable) {
        val file = read(database.uri, isKeyFile = false)
        val key = keyFile?.let { read(it.uri, isKeyFile = true) }
        try {
            withContext(Dispatchers.Default) {
                val opened = UnlockedDatabase.open(file, password, key)
                try {
                    opened to Opened(opened.summary(), opened.listGroup(null))
                } catch (e: KeePassException) {
                    opened.lock()
                    opened.close()
                    throw e
                }
            }
        } finally {
            key?.wipe()
        }
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

    private fun closeDatabase() {
        autoLock.disarm()
        groupJob?.cancel()
        searchJob?.cancel()
        entryJob?.cancel()
        database?.let {
            it.lock()
            it.close()
        }
        database = null
    }

    /** Empties the password field, and its undo history, which holds earlier copies of the text. */
    @OptIn(ExperimentalFoundationApi::class) // undoState
    private fun clearPassword() {
        password.clearText()
        password.undoState.clearHistory()
    }

    /** What the app shows straight after unlocking. */
    private class Opened(val summary: DatabaseSummary, val root: GroupListing)

    private class CantReadException(val keyFile: Boolean, cause: Throwable) : Exception(cause)
}
