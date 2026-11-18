package mn.lls.pocketkeepass

import android.app.Application
import java.io.File
import mn.lls.pocketkeepass.files.Backups
import mn.lls.pocketkeepass.files.Documents
import mn.lls.pocketkeepass.security.AutoLock
import mn.lls.pocketkeepass.security.Clipboard

class PocketKeePassApp : Application() {
    /** Locks the open database after a timeout or when the screen turns off. */
    lateinit var autoLock: AutoLock
        private set

    /** Copies entry fields, and clears the clipboard again after a timeout. */
    lateinit var clipboard: Clipboard
        private set

    /** The open database, shared by the app's screens and the autofill service. */
    lateinit var session: Session
        private set

    /** The previous version of the database, from just before each save. */
    lateinit var backups: Backups
        private set

    override fun onCreate() {
        super.onCreate()
        autoLock = AutoLock(this)
        backups = Backups(File(noBackupFilesDir, "backups"))
        session = Session(autoLock, Documents(this), backups)
        clipboard = Clipboard(this)
    }
}
