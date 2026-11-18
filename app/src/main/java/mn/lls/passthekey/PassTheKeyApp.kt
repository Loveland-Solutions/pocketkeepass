package mn.lls.passthekey

import android.app.Application
import mn.lls.passthekey.security.AutoLock
import mn.lls.passthekey.security.Clipboard

class PassTheKeyApp : Application() {
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
