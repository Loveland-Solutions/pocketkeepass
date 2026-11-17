package mn.lls.passthekey.security

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretsTest {
    @Test
    fun `UTF-8 encoding matches the JDK's, without going through a String`() {
        val samples = listOf(
            "",
            "test-password",
            "pässwörd ñ",               // 2-byte sequences
            "密码 パスワード",            // 3-byte sequences
            "🔑 key 🗝️",                // surrogate pairs (4-byte sequences)
            "\u0000\u007F\u0080߿ࠀ￿",
            "lone \uD83D high",         // lone surrogates become '?', as in the JDK
            "lone \uDD11 low",
            "end \uD83D",
        )
        for (sample in samples) {
            assertArrayEquals(sample.toByteArray(Charsets.UTF_8), StringBuilder(sample).toUtf8Bytes(), sample)
        }
    }

    @Test
    fun `reading a stream returns exactly its bytes, across buffer growth`() {
        for (size in listOf(0, 1, 16 * 1024, 16 * 1024 + 1, 100_000)) {
            val data = ByteArray(size) { (it * 31).toByte() }
            assertArrayEquals(data, ByteArrayInputStream(data).readAllWiping())
        }
    }

    @Test
    fun `reading copes with streams that return a few bytes at a time`() {
        val data = ByteArray(40_000) { it.toByte() }
        val trickle = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos == data.size) return -1
                val n = minOf(len, 7, data.size - pos)
                data.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }
        assertArrayEquals(data, trickle.readAllWiping())
    }

    @Test
    fun `wipe zeroes the array`() {
        val secret = "hunter2".toByteArray()
        secret.wipe()
        assertTrue(secret.all { it == 0.toByte() })
        assertEquals(7, secret.size)
    }
}
