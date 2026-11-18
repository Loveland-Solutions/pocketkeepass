package mn.lls.passthekey.security

/**
 * Tracks when the user last did something, against a clock that counts in milliseconds and
 * keeps counting while the phone sleeps (`SystemClock.elapsedRealtime` in the app).
 */
class IdleClock(private val now: () -> Long) {
    private var lastActivity = now()

    /** The user did something just now. */
    fun touch() {
        lastActivity = now()
    }

    /** When, on the same clock, the user will have been idle for [timeoutMs]. */
    fun deadline(timeoutMs: Long): Long = lastActivity + timeoutMs

    /** How long until the user will have been idle for [timeoutMs]. Zero once that's happened. */
    fun remaining(timeoutMs: Long): Long = (deadline(timeoutMs) - now()).coerceAtLeast(0)
}
