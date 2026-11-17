package mn.lls.passthekey

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mn.lls.passthekey.core.DatabaseSummary
import mn.lls.passthekey.core.KeePassException
import mn.lls.passthekey.core.UnlockedDatabase
import mn.lls.passthekey.files.Document
import mn.lls.passthekey.files.Documents
import mn.lls.passthekey.files.RecentDatabase
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
 * The decrypted database lives only here. It's locked (wiped) when the user locks it and when
 * this ViewModel is cleared, which happens when the activity finishes for good.
 */
class DatabaseViewModel(app: Application) : AndroidViewModel(app) {
    private val documents = Documents(app)
    private val recent = RecentDatabase(app)

    /**
     * The master password field's contents. It lives here rather than in `rememberSaveable`
     * state, because saved state is written to disk when Android stops the app.
     */
    val password = TextFieldState()

    private var database: UnlockedDatabase? = null

    private val _state = MutableStateFlow(
        recent.database?.let { DatabaseState.Locked(it, recent.keyFile) } ?: DatabaseState.NoDatabase,
    )
    val state: StateFlow<DatabaseState> = _state.asStateFlow()

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

            val (opened, summary) = outcome.getOrElse { e ->
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
            clearPassword()
            _state.value = DatabaseState.Unlocked(locked.database, locked.keyFile, summary)
        }
    }

    /** Wipes the decrypted database and goes back to the unlock screen. */
    fun lock() {
        closeDatabase()
        val unlocked = _state.value as? DatabaseState.Unlocked ?: return
        _state.value = DatabaseState.Locked(unlocked.database, unlocked.keyFile)
    }

    override fun onCleared() {
        closeDatabase()
        clearPassword()
    }

    /**
     * Reads the files and derives the key. That takes seconds on purpose, and it runs to the end
     * even if the caller is cancelled, so the result can always be wiped rather than abandoned.
     */
    private suspend fun open(
        database: Document,
        keyFile: Document?,
        password: ByteArray?,
    ): Pair<UnlockedDatabase, DatabaseSummary> = withContext(Dispatchers.IO + NonCancellable) {
        val file = read(database.uri, isKeyFile = false)
        val key = keyFile?.let { read(it.uri, isKeyFile = true) }
        try {
            withContext(Dispatchers.Default) {
                val opened = UnlockedDatabase.open(file, password, key)
                try {
                    opened to opened.summary()
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

    private class CantReadException(val keyFile: Boolean, cause: Throwable) : Exception(cause)
}
