package mn.lls.passthekey.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdleClockTest {
    private var now = 1_000L
    private val clock = IdleClock { now }

    @Test
    fun `time runs out after the timeout and not before`() {
        assertEquals(31_000L, clock.deadline(30_000))
        now += 29_999
        assertEquals(1L, clock.remaining(30_000))
        now += 1
        assertEquals(0L, clock.remaining(30_000))
        now += 60_000
        assertEquals(0L, clock.remaining(30_000)) // never negative
    }

    @Test
    fun `using the app starts the wait over`() {
        now += 20_000
        clock.touch()
        now += 20_000
        assertEquals(10_000L, clock.remaining(30_000))
        assertEquals(51_000L, clock.deadline(30_000)) // touched at 21,000
    }
}
