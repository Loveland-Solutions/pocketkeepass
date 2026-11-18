package mn.lls.pocketkeepass.security

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Helpers for handling secrets on the Kotlin side without leaving copies behind.
 *
 * The JVM gives no hard guarantees here: a `String` can't be wiped, and the garbage collector
 * may move arrays around. So the rules are to never turn a secret into a `String`, to build it
 * straight into the `ByteArray` that crosses the bridge, and to wipe every array once the
 * engine has it.
 */

/**
 * Encodes [text] as UTF-8 into a new array, without making a `String` on the way.
 * The caller wipes the result with [wipe].
 *
 * A lone surrogate becomes `?`, just as `String.toByteArray()` does.
 */
fun CharSequence.toUtf8Bytes(): ByteArray {
    val out = ByteArray(utf8Length())
    var o = 0
    forEachCodePoint { cp ->
        when {
            cp < 0x80 -> out[o++] = cp.toByte()
            cp < 0x800 -> {
                out[o++] = (0xC0 or (cp shr 6)).toByte()
                out[o++] = (0x80 or (cp and 0x3F)).toByte()
            }
            cp < 0x10000 -> {
                out[o++] = (0xE0 or (cp shr 12)).toByte()
                out[o++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                out[o++] = (0x80 or (cp and 0x3F)).toByte()
            }
            else -> {
                out[o++] = (0xF0 or (cp shr 18)).toByte()
                out[o++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                out[o++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                out[o++] = (0x80 or (cp and 0x3F)).toByte()
            }
        }
    }
    return out
}

private fun CharSequence.utf8Length(): Int {
    var n = 0
    forEachCodePoint { cp ->
        n += when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
    }
    return n
}

/** Calls [action] with each code point. A lone surrogate is reported as `?`. */
private inline fun CharSequence.forEachCodePoint(action: (Int) -> Unit) {
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> {
                action(Character.toCodePoint(c, this[i + 1]))
                i += 2
            }
            c.isSurrogate() -> {
                action('?'.code)
                i++
            }
            else -> {
                action(c.code)
                i++
            }
        }
    }
}

/** Overwrites the array with zeros. */
fun ByteArray.wipe() = fill(0)

/** Overwrites the array with zeros. */
fun CharArray.wipe() = fill('\u0000')

/**
 * Decodes UTF-8 into a new array of exactly the right size, without making a `String` on the
 * way. Invalid bytes become U+FFFD. The caller wipes the result with [wipe].
 */
fun ByteArray.decodeUtf8(): CharArray {
    // UTF-8 never takes fewer bytes than UTF-16 takes chars, so this is always big enough.
    val buffer = CharBuffer.allocate(size)
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .apply {
            decode(ByteBuffer.wrap(this@decodeUtf8), buffer, true)
            flush(buffer)
        }
    val result = buffer.array().copyOf(buffer.position())
    buffer.array().wipe()
    return result
}

/**
 * Reads the whole stream into an array of exactly the right size. Unlike `readBytes()`, every
 * intermediate buffer is wiped, so a key file leaves no stray copies behind.
 */
fun InputStream.readAllWiping(): ByteArray {
    var buffer = ByteArray(16 * 1024)
    var size = 0
    while (true) {
        if (size == buffer.size) {
            val bigger = buffer.copyOf(buffer.size * 2)
            buffer.wipe()
            buffer = bigger
        }
        val read = read(buffer, size, buffer.size - size)
        if (read < 0) break
        size += read
    }
    val result = buffer.copyOf(size)
    buffer.wipe()
    return result
}
