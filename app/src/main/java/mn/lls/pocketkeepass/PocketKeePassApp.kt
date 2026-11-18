package mn.lls.pocketkeepass

import android.app.Application
import mn.lls.pocketkeepass.security.AutoLock
import mn.lls.pocketkeepass.security.Clipboard

class PocketKeePassApp : Application() {
    /** Locks the open database after a timeout or when the screen turns off. */
    lateinit var autoLock: AutoLock
        private set

    /** Copies entry fields, and clears the clipboard again after a timeout. */
    lateinit var clipboard: Clipboard
        private set

    override fun onCreate() {
        super.onCreate()
        autoLock = AutoLock(this)
        clipboard = Clipboard(this)
    }
}
