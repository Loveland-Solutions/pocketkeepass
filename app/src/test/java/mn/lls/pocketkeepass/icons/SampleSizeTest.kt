package mn.lls.pocketkeepass.icons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SampleSizeTest {
    @Test
    fun `small icons are decoded whole`() {
        assertEquals(1, sampleSize(16, 16, 63))
        assertEquals(1, sampleSize(120, 120, 63))
        assertEquals(2, sampleSize(128, 128, 63)) // decoded at 64
    }

    @Test
    fun `big icons are reduced but never below the target`() {
        assertEquals(4, sampleSize(256, 256, 63)) // decoded at 64
        assertEquals(16, sampleSize(1024, 1024, 63))
        assertEquals(4, sampleSize(1024, 300, 63)) // the shorter side decides
    }

    @Test
    fun `sizes that aren't images, or are too big, are refused before decoding`() {
        assertNull(sampleSize(0, 16, 63))
        assertNull(sampleSize(-1, -1, 63))
        assertNull(sampleSize(1025, 16, 63))
        assertNull(sampleSize(30000, 30000, 63))
    }
}
