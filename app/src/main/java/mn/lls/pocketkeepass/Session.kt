package mn.lls.pocketkeepass

import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mn.lls.pocketkeepass.core.DatabaseSummary
import mn.lls.pocketkeepass.core.GroupListing
import mn.lls.pocketkeepass.core.KeePassException
import mn.lls.pocketkeepass.core.SyncReport
import mn.lls.pocketkeepass.core.UnlockedDatabase
import mn.lls.pocketkeepass.files.Backups
import mn.lls.pocketkeepass.files.Document
import mn.lls.pocketkeepass.files.Documents
import mn.lls.pocketkeepass.files.WriteCheckException
import mn.lls.pocketkeepass.files.fingerprint
import mn.lls.pocketkeepass.security.AutoLock
import mn.lls.pocketkeepass.security.wipe

/** Whether the open database's changes are in its file. */
sealed interface SaveState {
    /** Everything is saved (or nothing was changed). */
    data object Saved : SaveState

    /** A save is running. */
    data object Saving : SaveState

    /** There are changes that aren't in the file, and why. They're lost if the database locks. */
    data class Unsaved(val problem: SaveProblem) : SaveState
}

sealed interface SaveProblem {
    /**
     * Another app or device changed the file since it was opened, and it couldn't be merged
     * with ours (see [SyncProblem]). Saving would replace that.
     */
    data object Conflict : SaveProblem

    /** The file can't be written: it's gone, or the storage app only allows reading it. */
    data object CantWrite : SaveProblem

    /** The file didn't read back the same after writing. */
    data object WriteCheckFailed : SaveProblem

    /** The engine refused to save. Shown with [mn.lls.pocketkeepass.ui.userMessage]. */
    data class Engine(val error: KeePassException) : SaveProblem
}

/** Changes that came in from another app or device, and when. */
data class Incoming(val at: Long, val report: SyncReport)

/** Why the file as another app or device left it couldn't be taken in. */
enum class SyncProblem {
    /** The file can't be read right now (the storage app may be offline), or is damaged. */
    CANT_READ,

    /** Our master key no longer opens it: the master password or key file was changed elsewhere. */
    KEY_CHANGED,

    /** It holds a different database now. */
    OTHER_DATABASE,
}

/**
 * How the open database's file stands with other devices, for the sync line and its dialog.
 * Nothing secret.
 */
data class SyncStatus(
    /** The app holding the file (e.g. "Drive"), or null if it's on this phone. */
    val storage: String? = null,
    /** When our changes last went into the file, handed to [storage] to upload. */
    val savedAt: Long? = null,
    /** When the file was last read to look for changes made elsewhere. */
    val checkedAt: Long? = null,
    /** The last changes that came in from elsewhere. */
    val incoming: Incoming? = null,
    /** A check the user asked for is running. */
    val checking: Boolean = false,
    /** Why the last check couldn't take the file in, or null if it could. */
    val problem: SyncProblem? = null,
)

/**
 * The open database, for the whole app ([PocketKeePassApp.session]). The decrypted database
 * lives only here.
 *
 * It belongs to the app rather than to one screen because three things use it: the app itself,
 * the autofill service, and the screen autofill shows on top of other apps. Whichever of them
 * unlocks it, the others see it open, and when it locks, it's locked for all of them.
 *
 * It's locked (wiped) by the Lock button, by auto-lock (timeout, screen off), and when the
 * app's own screen is closed for good. Everything here runs on the main thread.
 *
 * Every change to the database is saved straight away ([changed]). Saves run in the app's own
 * scope, not a screen's, and can't be cancelled halfway: a file half-written because the user
 * left a screen is the worst outcome there is. Before each one, the file as it was and the new
 * bytes are copied to [Backups], in case the write itself is cut short.
 *
 * The file is shared with other devices through the storage app (Drive, Dropbox, and so on), so
 * while the app is on screen it's read every [CHECK_INTERVAL_MS] (and whenever the storage app
 * says it changed) to take in changes made elsewhere ([sync]). With nothing of our own unsaved,
 * the other version simply replaces ours; otherwise the engine merges the two and the result is
 * saved. A save that finds the file changed merges the same way instead of overwriting it.
 */
class Session(private val autoLock: AutoLock, private val documents: Documents, private val backups: Backups) {
    /** An unlocked database, with what the app shows straight after unlocking it. */
    class Open(
        val database: UnlockedDatabase,
        val document: Document,
        val keyFile: Document?,
        val summary: DatabaseSummary,
        val root: GroupListing,
        /** The [fingerprint] of the file as we last read or wrote it. */
        @Volatile var fileFingerprint: ByteArray,
    ) {
        /**
         * The engine's [UnlockedDatabase.changeCount] when the database in memory last matched
         * the file. Anything changed since then is ours alone, to be merged rather than replaced.
         */
        @Volatile var cleanCount: ULong = 0u
    }

    private val scope = MainScope()

    private val _open = MutableStateFlow<Open?>(null)

    /** The open database, or null while it's locked. */
    val open: StateFlow<Open?> = _open.asStateFlow()

    private val _revision = MutableStateFlow(0L)

    /** Goes up by one after every change to the open database, so screens know to re-read it. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Saved)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private var saveJob: Job? = null

    /** Another change arrived while a save was running: save again when it's done. */
    private var saveAgain = false

    /** Locked while a save was running: wipe it when the save is done. */
    private var wipeAfterSave: Open? = null

    /** Saving and checking for changes take turns with the file. */
    private val fileLock = Mutex()

    private val _sync = MutableStateFlow(SyncStatus())
    val sync: StateFlow<SyncStatus> = _sync.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 4)

    /** Each time changes made elsewhere come in, for saying so. */
    val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    /** Whether the app's own screen is showing, so the file is worth watching. */
    private var visible = false
    private var watchJob: Job? = null
    private var checkJob: Job? = null
    private var stopObserving: (() -> Unit)? = null

    /** A database was just unlocked. Any other one is locked first. */
    fun start(open: Open) {
        lock()
        // The file opened, so it isn't damaged: a save to it that was cut short is no longer needed.
        backups.finished(open.document.uri.toString())
        _saveState.value = SaveState.Saved
        open.cleanCount = try {
            open.database.changeCount()
        } catch (_: IllegalStateException) {
            0u
        }
        _sync.value = SyncStatus(storage = documents.storageName(open.document.uri), checkedAt = now())
        _open.value = open
        autoLock.arm(::lock)
        startWatching()
    }

    /** The app's own screen came into view, or left it. Changes elsewhere are watched for meanwhile. */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (visible) startWatching() else stopWatching()
    }

    /** Reads the file now, for changes made elsewhere (pull to refresh, "Check now"). */
    fun checkNow() = check(manual = true)

    private fun startWatching() {
        val open = _open.value ?: return
        if (!visible || watchJob?.isActive == true) return
        stopObserving = documents.observe(open.document.uri) { check() }
        watchJob = scope.launch {
            while (true) {
                check()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        stopObserving?.invoke()
        stopObserving = null
    }

    /** Reads the file and takes in what changed elsewhere, if anything did. */
    private fun check(manual: Boolean = false) {
        val open = _open.value ?: return
        if (checkJob?.isActive == true) return
        if (manual) _sync.update { it.copy(checking = true) }
        checkJob = scope.launch {
            val outcome = withContext(Dispatchers.IO + NonCancellable) {
                fileLock.withLock {
                    if (_open.value !== open) return@withLock null
                    val current = try {
                        documents.read(open.document.uri)
                    } catch (e: Exception) {
                        if (problemFor(e) == null) throw e
                        return@withLock Taken.Failed(SyncProblem.CANT_READ)
                    }
                    if (fingerprint(current).contentEquals(open.fileFingerprint)) Taken.Same else sync(open, current)
                }
            }
            _sync.update { it.copy(checking = false) }
            if (_open.value !== open || outcome == null) return@launch
            when (outcome) {
                // sync() records what it took in; these two never reached it.
                Taken.Same -> _sync.update { it.copy(checkedAt = now(), problem = null) }
                is Taken.Failed -> _sync.update { it.copy(checkedAt = now(), problem = outcome.problem) }
                is Taken.Changes -> if (outcome.report.merged) save()
            }
        }
    }

    private sealed interface Taken {
        data object Same : Taken
        class Changes(val report: SyncReport) : Taken
        class Failed(val problem: SyncProblem) : Taken
    }

    /**
     * Takes in [current], the file as another app or device left it: replaces our database with
     * it if we have nothing unsaved, merges the two otherwise ([SyncReport.merged]: then the
     * result needs saving). Records the outcome in [sync]. Runs key derivation, so it's slow.
     */
    private suspend fun sync(open: Open, current: ByteArray): Taken {
        val taken = withContext(Dispatchers.Default) {
            try {
                val report = open.database.syncWithFile(current, open.cleanCount)
                open.fileFingerprint = fingerprint(current)
                if (!report.merged) open.cleanCount = open.database.changeCount()
                Taken.Changes(report)
            } catch (_: KeePassException.WrongKey) {
                Taken.Failed(SyncProblem.KEY_CHANGED)
            } catch (_: KeePassException.Invalid) {
                Taken.Failed(SyncProblem.OTHER_DATABASE)
            } catch (_: KeePassException.Locked) {
                Taken.Same
            } catch (_: IllegalStateException) {
                Taken.Same // closed by lock() before the call started
            } catch (_: KeePassException) {
                Taken.Failed(SyncProblem.CANT_READ) // damaged, or still arriving
            }
        }
        val at = now()
        when (taken) {
            Taken.Same -> _sync.update { it.copy(checkedAt = at, problem = null) }
            is Taken.Failed -> _sync.update { it.copy(checkedAt = at, problem = taken.problem) }
            is Taken.Changes -> {
                val incoming = Incoming(at, taken.report)
                _sync.update {
                    it.copy(checkedAt = at, problem = null, incoming = if (taken.report.hasChanges()) incoming else it.incoming)
                }
                _revision.update { it + 1 }
                if (taken.report.hasChanges()) _incoming.tryEmit(incoming)
            }
        }
        return taken
    }

    /**
     * Wipes the decrypted database. Safe to call when nothing is open. Unsaved changes are lost.
     *
     * A save that's running finishes first (it already has the encrypted bytes, so the change
     * still reaches the file), and the database is wiped right after. Waiting for it here would
     * freeze the screen for as long as key derivation takes.
     */
    fun lock() {
        val open = _open.value ?: return
        autoLock.disarm()
        stopWatching()
        checkJob?.cancel()
        _open.value = null
        _saveState.value = SaveState.Saved
        _sync.value = SyncStatus()
        if (saveJob?.isActive == true) wipeAfterSave = open else wipe(open)
    }

    /** The open database was just changed in memory. Screens re-read it, and it's saved. */
    fun changed() {
        _revision.value++
        save()
    }

    /**
     * Saves the open database to its file. Unless [overwrite] is true, a file that another app
     * or device changed since we read it isn't replaced: the state becomes
     * [SaveProblem.Conflict] instead, for the user to decide.
     */
    fun save(overwrite: Boolean = false) {
        val open = _open.value ?: return
        if (saveJob?.isActive == true) {
            saveAgain = true
            return
        }
        _saveState.value = SaveState.Saving
        saveJob = scope.launch {
            val problem = withContext(Dispatchers.IO + NonCancellable) {
                fileLock.withLock { write(open, overwrite) }
            }
            wipeAfterSave?.let {
                wipeAfterSave = null
                wipe(it)
            }
            if (_open.value !== open) return@launch // locked meanwhile
            _saveState.value = problem?.let(SaveState::Unsaved) ?: SaveState.Saved
            if (saveAgain) {
                saveAgain = false
                if (problem == null) save()
            }
        }
    }

    /**
     * Writes the open database to a new file the user picked ("save a copy"), leaving the
     * database's own file alone. Returns null on success, or what went wrong.
     */
    suspend fun saveCopy(uri: Uri): SaveProblem? {
        val open = _open.value ?: return null
        return withContext(Dispatchers.IO + NonCancellable) {
            val bytes = try {
                withContext(Dispatchers.Default) { open.database.save() }
            } catch (e: KeePassException) {
                return@withContext SaveProblem.Engine(e)
            }
            writeChecked(uri, bytes)
        }
    }

    /**
     * Throws away the changes and opens the file as it is now, with the same master key. Used
     * after a conflict. Returns false if the master key no longer opens it (it was changed
     * elsewhere); the database is locked then, for the user to unlock with the new one.
     */
    suspend fun reload(): Boolean {
        val open = _open.value ?: return false
        val reopened = withContext(Dispatchers.IO + NonCancellable) {
            try {
                val file = documents.read(open.document.uri)
                val keyHash = open.database.keyHash()
                try {
                    val db = UnlockedDatabase.openWithKeyHash(file, keyHash)
                    Open(db, open.document, open.keyFile, db.summary(), db.listGroup(null), fingerprint(file))
                } finally {
                    keyHash.wipe()
                }
            } catch (_: KeePassException) {
                null
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
        if (_open.value !== open) {
            reopened?.database?.let { it.lock(); it.close() }
            return false
        }
        if (reopened == null) {
            lock()
            return false
        }
        start(reopened)
        return true
    }

    /**
     * Saves to the database's own file. Returns null on success, or what went wrong. A file
     * changed elsewhere is merged with ours first (unless [overwrite]), and the result saved.
     * Hold [fileLock].
     */
    private suspend fun write(open: Open, overwrite: Boolean): SaveProblem? {
        // Each round merges what another device saved meanwhile; it would take a device saving
        // every few seconds to need more than a couple.
        repeat(MAX_MERGES + 1) {
            val count: ULong
            val bytes = try {
                count = open.database.changeCount()
                withContext(Dispatchers.Default) { open.database.save() }
            } catch (_: KeePassException.Locked) {
                return null // locked before the save started; nothing to do
            } catch (_: IllegalStateException) {
                return null // closed by lock() before the call started
            } catch (e: KeePassException) {
                return SaveProblem.Engine(e)
            }
            val uri = open.document.uri
            val current = try {
                documents.read(uri)
            } catch (e: Exception) {
                // Replacing it anyway: the write itself will say if the file is really out of reach.
                if (overwrite) null else return problemFor(e) ?: throw e
            }
            if (current != null) {
                val known = fingerprint(current).contentEquals(open.fileFingerprint)
                if (!overwrite && !known) {
                    when (val taken = sync(open, current)) {
                        is Taken.Changes -> if (taken.report.merged) return@repeat else return null
                        else -> return SaveProblem.Conflict
                    }
                }
                // Replacing another app's version: keep it only if it's a database our key opens, so
                // a damaged or foreign file never pushes out a backup that works.
                if (known || opensWithOurKey(open, current)) keepBackup { backups.keep(uri.toString(), current) }
            }
            keepBackup { backups.keepUnfinished(uri.toString(), bytes) }
            return writeChecked(uri, bytes).also {
                if (it == null) {
                    open.fileFingerprint = fingerprint(bytes)
                    open.cleanCount = count
                    backups.finished(uri.toString())
                    _sync.update { status -> status.copy(savedAt = now()) }
                }
            }
        }
        return SaveProblem.Conflict
    }

    /**
     * Keeps a copy in [backups] before the file is overwritten. If that fails (the phone's
     * storage is full), the save goes ahead: older copies are still there, and refusing would
     * leave the changes to be lost at the next lock.
     */
    private inline fun keepBackup(keep: () -> Unit) {
        try {
            keep()
        } catch (_: IOException) {
        }
    }

    /** Whether [file] opens with the open database's master key. Runs key derivation, so it's slow. */
    private suspend fun opensWithOurKey(open: Open, file: ByteArray): Boolean = withContext(Dispatchers.Default) {
        val keyHash = try {
            open.database.keyHash()
        } catch (_: KeePassException) {
            return@withContext false
        } catch (_: IllegalStateException) {
            return@withContext false
        }
        try {
            UnlockedDatabase.openWithKeyHash(file, keyHash).let {
                it.lock()
                it.close()
            }
            true
        } catch (_: KeePassException) {
            false
        } finally {
            keyHash.wipe()
        }
    }

    private fun writeChecked(uri: Uri, bytes: ByteArray): SaveProblem? = try {
        documents.write(uri, bytes)
        null
    } catch (e: Exception) {
        problemFor(e) ?: throw e
    }

    private fun problemFor(e: Exception): SaveProblem? = when (e) {
        is WriteCheckException -> SaveProblem.WriteCheckFailed
        is FileNotFoundException, is SecurityException, is IOException, is IllegalArgumentException,
        is UnsupportedOperationException -> SaveProblem.CantWrite
        else -> null
    }

    private fun now() = System.currentTimeMillis()

    private fun wipe(open: Open) {
        open.database.lock()
        open.database.close()
    }
}

/** How often the file is read for changes made elsewhere while the app is on screen. */
const val CHECK_INTERVAL_MS = 30_000L

/** How many times one save merges in another device's changes before giving up. */
private const val MAX_MERGES = 3

/** Whether anything at all came in. */
fun SyncReport.hasChanges() =
    entriesAdded + entriesChanged + entriesRemoved + groupsChanged > 0u
