package mn.lls.passthekey

import android.app.Application
import mn.lls.passthekey.security.AutoLock

class PassTheKeyApp : Application() {
    /** Locks the open database after a timeout or when the screen turns off. */
    lateinit var autoLock: AutoLock
        private set

    override fun onCreate() {
        super.onCreate()
        autoLock = AutoLock(this)
    }
}
