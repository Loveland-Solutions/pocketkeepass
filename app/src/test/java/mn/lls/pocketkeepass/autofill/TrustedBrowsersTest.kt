package mn.lls.pocketkeepass.autofill

import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reading the list of browsers whose word autofill takes for which website is open. */
class TrustedBrowsersTest {
    private val certificate = "0123456789abcdef".repeat(4)

    @Test
    fun `a line is a package name and its certificates`() {
        val browsers = parseTrustedBrowsers(
            sequenceOf(
                "# a comment",
                "",
                "com.example.browser $certificate",
                "com.example.two   $certificate ${"ff".repeat(32)}  # two signing keys",
            ),
        )
        assertEquals(setOf("com.example.browser", "com.example.two"), browsers.keys)
        assertEquals(2, browsers.getValue("com.example.two").size)
        val bytes = browsers.getValue("com.example.browser").single()
        assertEquals(32, bytes.size)
        assertArrayEquals(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte()), bytes.copyOf(8))
    }

    @Test
    fun `a damaged line trusts nothing`() {
        val damaged = sequenceOf(
            "com.example.nocert",
            "com.example.short ${certificate.dropLast(2)}",
            "com.example.upper ${certificate.uppercase()}",
            "com.example.nothex ${"zz".repeat(32)}",
            "com.example.onebad $certificate nonsense",
        )
        assertTrue(parseTrustedBrowsers(damaged).isEmpty())
    }

    @Test
    fun `the list that ships parses in full`() {
        val lines = File("src/main/res/raw/trusted_browsers.txt").readLines()
        val entries = lines.count { it.isNotBlank() && !it.startsWith("#") }
        val browsers = parseTrustedBrowsers(lines.asSequence())
        assertEquals(entries, browsers.size)
        assertTrue(browsers.size > 20)
        assertTrue("com.android.chrome" in browsers && "org.mozilla.firefox" in browsers)
        // Apps on Google's list that aren't browsers are left out when the list is made.
        assertFalse("com.google.android.gms" in browsers)
    }
}
