package mn.lls.pocketkeepass

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mn.lls.pocketkeepass.core.DatabaseSummary
import mn.lls.pocketkeepass.core.GroupListing
import mn.lls.pocketkeepass.core.UnlockedDatabase
import mn.lls.pocketkeepass.files.Document
import mn.lls.pocketkeepass.security.AutoLock

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
 */
class Session(private val autoLock: AutoLock) {
    /** An unlocked database, with what the app shows straight after unlocking it. */
    class Open(
        val database: UnlockedDatabase,
        val document: Document,
        val keyFile: Document?,
        val summary: DatabaseSummary,
        val root: GroupListing,
    )

    private val _open = MutableStateFlow<Open?>(null)

    /** The open database, or null while it's locked. */
    val open: StateFlow<Open?> = _open.asStateFlow()

    /** A database was just unlocked. Any other one is locked first. */
    fun start(open: Open) {
        lock()
        _open.value = open
        autoLock.arm(::lock)
    }

    /** Wipes the decrypted database. Safe to call when nothing is open. */
    fun lock() {
        val open = _open.value ?: return
        autoLock.disarm()
        _open.value = null
        open.database.lock()
        open.database.close()
    }
}
