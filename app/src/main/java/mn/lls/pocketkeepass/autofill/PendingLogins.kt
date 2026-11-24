package mn.lls.pocketkeepass.autofill

import android.os.SystemClock
import java.security.SecureRandom
import mn.lls.pocketkeepass.security.wipe

/**
 * Logins the user asked to save, between Android's save request and the screen that saves them.
 *
 * They stay in this process's memory, never in an intent: an intent's extras pass through
 * Android's own process and stay there as long as its pending intent does. The intent carries
 * only a random token for [take]. A login not taken within [LIFETIME_MS] is wiped, and so is
 * everything if the process ends.
 */
class PendingLogins {
    /** What was typed, as UTF-8. The username may be empty; the password never is. */
    class Login(val fill: Fill, val username: ByteArray, val password: ByteArray) {
        internal val created = SystemClock.elapsedRealtime()

        fun wipe() {
            username.wipe()
            password.wipe()
        }
    }

    private val logins = HashMap<String, Login>()
    private val random = SecureRandom()

    /** Keeps [login] and returns the token to take it with. */
    @Synchronized
    fun put(login: Login): String {
        dropExpired()
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        logins[token] = login
        return token
    }

    /** The login kept under [token], once: it's forgotten here. Null if it's gone or too old. */
    @Synchronized
    fun take(token: String): Login? {
        dropExpired()
        return logins.remove(token)
    }

    private fun dropExpired() {
        val now = SystemClock.elapsedRealtime()
        val expired = logins.filterValues { now - it.created !in 0..LIFETIME_MS }
        for ((token, login) in expired) {
            logins.remove(token)
            login.wipe()
        }
    }

    private companion object {
        /** Long enough to unlock the database first, not long enough to forget about it. */
        const val LIFETIME_MS = 10 * 60 * 1000L
    }
}
