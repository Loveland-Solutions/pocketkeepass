package mn.lls.pocketkeepass.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasswordDueTest {
    private val day = 24L * 60 * 60 * 1000
    private val typed = PasswordTyped(bootCount = 5, elapsedMs = 1_000_000)

    @Test
    fun `quick unlock works until the interval is up`() {
        assertFalse(isPasswordDue(typed, 5, typed.elapsedMs, 14))
        assertFalse(isPasswordDue(typed, 5, typed.elapsedMs + 14 * day - 1, 14))
        assertTrue(isPasswordDue(typed, 5, typed.elapsedMs + 14 * day, 14))
        assertTrue(isPasswordDue(typed, 5, typed.elapsedMs + 2 * day, 1))
    }

    @Test
    fun `a restart makes the password due`() {
        assertTrue(isPasswordDue(typed, 6, typed.elapsedMs + 1000, 30))
        // A phone without a boot count (-1 both times): the uptime clock went back to zero.
        val noCount = PasswordTyped(bootCount = -1, elapsedMs = 1_000_000)
        assertTrue(isPasswordDue(noCount, -1, 5_000, 30))
        assertFalse(isPasswordDue(noCount, -1, 2_000_000, 30))
    }

    @Test
    fun `never typed since the rule came in means due`() {
        assertTrue(isPasswordDue(null, 5, 1_000_000, 30))
    }
}
