package mn.lls.pocketkeepass

import android.net.Uri
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
import mn.lls.pocketkeepass.files.Connection
import mn.lls.pocketkeepass.files.Document
import mn.lls.pocketkeepass.files.Documents
import mn.lls.pocketkeepass.files.WriteCheckException
import mn.lls.pocketkeepass.files.fingerprint
import mn.lls.pocketkeepass.icons.CustomIcons
import mn.lls.pocketkeepass.security.AutoLock
import mn.lls.pocketkeepass.security.wipe

/** Whether the open database's changes are in its file. */
sealed interface SaveState {
    /** Everything is saved (or nothing was changed). */
    data object Saved : SaveState

    /** A save is running. */
    data object Saving : SaveState

    /**
     * The changes are saved on this phone ([Backups]) but not in the file: it's held by a cloud
     * storage app and the phone is offline. They go into the file, merged with whatever it holds
     * by then, once the phone is back online, and survive a lock meanwhile.
     */
    data object Waiting : SaveState

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

    /**
     * Its key derivation settings changed. They can't be checked before they're run, so a file
     * could ask for any amount of work: that only happens when the user unlocks it.
     */
    SETTINGS_CHANGED,
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
 * says it changed) to take in changes made elsewhere ([sync]). The engine always merges the two
 * versions, so that a file that went back to an older version (a device that was offline, or
 * someone putting an old copy back) can't undo what was saved here; if ours adds nothing, the
 * result is just the file's version. Otherwise the result is saved. A save that finds the file
 * changed merges the same way instead of overwriting it.
 *
 * Two more guards keep a cloud storage app from losing changes. Some (Google Drive, at least)
 * take a save made offline into their own copy and upload it later over whatever the file became
 * meanwhile, without checking:
 *
 * - offline, a save to a file such an app holds stays on this phone ([SaveState.Waiting]) and
 *   goes into the file once the phone has been back online for [RECONNECT_DELAY_MS], merged
 *   with what the file holds by then. So we never overwrite another device's change unseen;
 * - each unlock merges what this phone last saved ([Backups.ours]) into the file as read, so
 *   if another device or app overwrote our save without it, it comes back. While the database
 *   stays open, the checks above do the same from memory.
 */
class Session(
    private val autoLock: AutoLock,
    private val documents: Documents,
    private val backups: Backups,
    private val connection: Connection,
) {
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

    /** The open database's custom icons, decoded once each. Emptied on lock. */
    val customIcons = CustomIcons()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Saved)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private var saveJob: Job? = null

    /**
     * Another change arrived while a save was running: save again when it's done. Possibly to
     * another database than the running save's, if it was locked and another unlocked meanwhile.
     */
    private var saveAgain = false

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

    /** Merging what this phone last saved into the database just unlocked. Saves wait for it. */
    private var restoreJob: Job? = null
    private var reconnectJob: Job? = null

    init {
        connection.onReconnect(::reconnected)
    }

    /** A database was just unlocked. Any other one is locked first. */
    fun start(open: Open) {
        lock()
        _saveState.value = SaveState.Saved
        open.cleanCount = try {
            open.database.changeCount()
        } catch (_: IllegalStateException) {
            0u
        }
        _sync.value = SyncStatus(storage = documents.storageName(open.document.uri), checkedAt = now())
        _open.value = open
        autoLock.arm(::lock)
        restoreJob = scope.launch { restoreOurs(open) }
        startWatching()
    }

    /**
     * Merges what this phone last saved to the file (or a save that was held back or cut short)
     * into the database as just read from it, and saves if that brought anything back: another
     * device or app overwrote the file without our change. Usually the file is exactly what we
     * saved, and nothing runs.
     */
    private suspend fun restoreOurs(open: Open) {
        val location = open.document.uri.toString()
        val restored = withContext(Dispatchers.IO + NonCancellable) {
            fileLock.withLock {
                if (_open.value !== open) return@withLock false
                val ours = backups.ours(location) ?: return@withLock false
                if (fingerprint(ours).contentEquals(open.fileFingerprint)) {
                    backups.finished(location)
                    return@withLock false
                }
                val report = withContext(Dispatchers.Default) {
                    try {
                        open.database.mergeSavedCopy(ours)
                    } catch (_: KeePassException) {
                        // Another master key since (changed elsewhere), another database, or a copy
                        // that won't open. The copy stays until the next save replaces it.
                        null
                    } catch (_: IllegalStateException) {
                        null // closed by lock() before the call started
                    }
                } ?: return@withLock false
                if (!report.merged) backups.finished(location)
                report.merged
            }
        }
        if (restored && _open.value === open) changed()
    }

    /** The phone is back online: write a save that was waiting for it, after [RECONNECT_DELAY_MS]. */
    private fun reconnected() {
        if (_saveState.value != SaveState.Waiting) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (_saveState.value == SaveState.Waiting && connection.isOnline()) save()
        }
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
                // In case the reconnect callback was missed (the app was frozen meanwhile).
                val waited = _saveState.value == SaveState.Waiting && (connection.onlineFor() ?: 0) >= RECONNECT_DELAY_MS
                if (waited) save() else check()
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
                    } catch (_: Exception) {
                        // Whatever the storage app throws: it can throw anything, every 30 s.
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
     * Takes in [current], the file as another app or device left it, merged with ours. If ours
     * adds nothing to it, the database now matches the file; otherwise ([SyncReport.merged])
     * the result needs saving. Records the outcome in [sync]. Runs key derivation, so it's slow.
     */
    private suspend fun sync(open: Open, current: ByteArray): Taken {
        val taken = withContext(Dispatchers.Default) {
            try {
                val report = open.database.syncWithFile(current, open.cleanCount)
                open.fileFingerprint = fingerprint(current)
                // The engine's count from the moment it took the file in, so a change made just
                // after isn't counted as already in the file.
                if (!report.merged) open.cleanCount = report.changeCount
                Taken.Changes(report)
            } catch (_: KeePassException.WrongKey) {
                Taken.Failed(SyncProblem.KEY_CHANGED)
            } catch (_: KeePassException.Invalid) {
                Taken.Failed(SyncProblem.OTHER_DATABASE)
            } catch (_: KeePassException.SettingsChanged) {
                Taken.Failed(SyncProblem.SETTINGS_CHANGED)
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
     * The wipe runs off the main thread: it waits for any engine call still running (a save's
     * or a check's key derivation), and the screen mustn't freeze meanwhile. It doesn't wait
     * for the storage app: a save that already has its encrypted bytes still writes them, so
     * the change reaches the file, but a slow or stuck storage app can't keep the database
     * decrypted in memory.
     */
    fun lock() {
        val open = _open.value ?: return
        autoLock.disarm()
        stopWatching()
        checkJob?.cancel()
        reconnectJob?.cancel()
        _open.value = null
        _saveState.value = SaveState.Saved
        _sync.value = SyncStatus()
        customIcons.reset()
        scope.launch(Dispatchers.Default + NonCancellable) { wipe(open) }
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
        _saveState.value = SaveState.Saving
        if (saveJob?.isActive == true) {
            saveAgain = true
            return
        }
        saveJob = scope.launch {
            // What this phone saved before must be in the database before anything is written.
            restoreJob?.join()
            val written = withContext(Dispatchers.IO + NonCancellable) {
                fileLock.withLock { write(open, overwrite) }
            }
            val again = saveAgain
            saveAgain = false
            if (_open.value !== open) {
                // Locked meanwhile. A change to a database unlocked since is still to be saved.
                if (again) save()
                return@launch
            }
            if (again && written !is Written.Failed) {
                // Straight on to the next save: "Saved" in between would be untrue, and a
                // passkey waiting for its save would take it as done.
                save()
                return@launch
            }
            _saveState.value = when (written) {
                Written.Done -> SaveState.Saved
                Written.Held -> SaveState.Waiting
                is Written.Failed -> SaveState.Unsaved(written.problem)
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

    private sealed interface Written {
        /** In the file. */
        data object Done : Written

        /** Kept on this phone until it's back online ([SaveState.Waiting]). */
        data object Held : Written

        class Failed(val problem: SaveProblem) : Written
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
            } catch (_: Exception) {
                null // the engine's errors, a closed database, or whatever the storage app throws
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
     * Saves to the database's own file. A file changed elsewhere is merged with ours first
     * (unless [overwrite]), and the result saved. Offline, a file a cloud storage app holds
     * isn't touched: the save is kept on this phone instead ([Written.Held]). Hold [fileLock].
     */
    private suspend fun write(open: Open, overwrite: Boolean): Written {
        // Each round merges what another device saved meanwhile; it would take a device saving
        // every few seconds to need more than a couple.
        repeat(MAX_MERGES + 1) {
            val count: ULong
            val bytes = try {
                count = open.database.changeCount()
                withContext(Dispatchers.Default) { open.database.save() }
            } catch (_: KeePassException.Locked) {
                return Written.Done // locked before the save started; nothing to do
            } catch (_: IllegalStateException) {
                return Written.Done // closed by lock() before the call started
            } catch (e: KeePassException) {
                return Written.Failed(SaveProblem.Engine(e))
            }
            val uri = open.document.uri
            if (!documents.isOnThisPhone(uri) && !connection.isOnline()) {
                // The storage app would upload this later over whatever the file is by then.
                return try {
                    backups.keepUnfinished(uri.toString(), bytes)
                    Written.Held
                } catch (_: IOException) {
                    Written.Failed(SaveProblem.CantWrite) // nowhere to keep it: in memory only
                }
            }
            val current = try {
                documents.read(uri)
            } catch (e: Exception) {
                // Replacing it anyway: the write itself will say if the file is really out of reach.
                if (overwrite) null else return Written.Failed(problemFor(e))
            }
            if (current != null) {
                val known = fingerprint(current).contentEquals(open.fileFingerprint)
                if (!overwrite && !known) {
                    when (val taken = sync(open, current)) {
                        is Taken.Changes -> if (taken.report.merged) return@repeat else return Written.Done
                        else -> return Written.Failed(SaveProblem.Conflict)
                    }
                }
                // Replacing another app's version: keep it only if it's a database our key opens, so
                // a damaged or foreign file never pushes out a backup that works.
                if (known || opensWithOurKey(open, current)) keepBackup { backups.keep(uri.toString(), current) }
            }
            keepBackup { backups.keepUnfinished(uri.toString(), bytes) }
            val problem = writeChecked(uri, bytes) ?: run {
                open.fileFingerprint = fingerprint(bytes)
                open.cleanCount = count
                backups.saved(uri.toString())
                _sync.update { status -> status.copy(savedAt = now()) }
                return Written.Done
            }
            return Written.Failed(problem)
        }
        return Written.Failed(SaveProblem.Conflict)
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
        problemFor(e)
    }

    /**
     * What a storage app's exception means for a save. A storage app can throw anything at us
     * (database errors, null pointers, parcel errors all cross from its process), and none of
     * it may crash the app and take unsaved changes with it.
     */
    private fun problemFor(e: Exception): SaveProblem = when (e) {
        is WriteCheckException -> SaveProblem.WriteCheckFailed
        else -> SaveProblem.CantWrite
    }

    private fun now() = System.currentTimeMillis()

    private fun wipe(open: Open) {
        open.database.lock()
        open.database.close()
    }
}

/** How often the file is read for changes made elsewhere while the app is on screen. */
const val CHECK_INTERVAL_MS = 30_000L

/**
 * How long the phone must be back online before a save that waited for it is written, so the
 * storage app has had a moment to fetch what other devices saved meanwhile.
 */
const val RECONNECT_DELAY_MS = 10_000L

/** How many times one save merges in another device's changes before giving up. */
private const val MAX_MERGES = 3

/** Whether anything at all came in. */
fun SyncReport.hasChanges() =
    entriesAdded + entriesChanged + entriesRemoved + groupsChanged > 0u
