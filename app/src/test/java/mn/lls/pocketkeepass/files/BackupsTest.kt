package mn.lls.pocketkeepass.files

import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class BackupsTest {
    @TempDir
    lateinit var root: File

    private val dir by lazy { File(root, "backups") }
    private val backups by lazy { Backups(dir) }

    private val a = "content://provider/document/a.kdbx"
    private val b = "content://provider/document/b.kdbx"

    private fun read(location: String) = backups.read(location, backups.find(location)!!)

    @Test
    fun `keeps one previous version per database, replacing the one before`() {
        assertNull(backups.find(a))
        backups.keep(a, byteArrayOf(1, 2, 3))
        backups.keep(a, byteArrayOf(4, 5))
        backups.keep(b, byteArrayOf(6))
        assertFalse(backups.find(a)!!.unfinished)
        assertArrayEquals(byteArrayOf(4, 5), read(a))
        assertArrayEquals(byteArrayOf(6), read(b))
        // No temporary files left behind, and no file named after the location itself.
        assertEquals(2, dir.list()!!.size)
        dir.list()!!.forEach { assertTrue(it.matches(Regex("[0-9a-f]{64}\\.kdbx")), it) }
    }

    @Test
    fun `offers an unfinished save first, until it's finished`() {
        backups.keep(a, byteArrayOf(1))
        backups.keepUnfinished(a, byteArrayOf(2))
        val unfinished = backups.find(a)!!
        assertTrue(unfinished.unfinished)
        assertArrayEquals(byteArrayOf(2), backups.read(a, unfinished))
        backups.finished(a)
        val previous = backups.find(a)!!
        assertFalse(previous.unfinished)
        assertArrayEquals(byteArrayOf(1), backups.read(a, previous))
        backups.finished(a) // nothing left to finish: does nothing
        assertArrayEquals(byteArrayOf(1), read(a))
    }

    @Test
    fun `an unfinished save alone is found too`() {
        backups.keepUnfinished(a, byteArrayOf(9))
        assertTrue(backups.find(a)!!.unfinished)
        assertArrayEquals(byteArrayOf(9), read(a))
    }

    @Test
    fun `moves both copies to a new location`() {
        backups.keep(a, byteArrayOf(7))
        backups.keepUnfinished(a, byteArrayOf(8))
        backups.move(a, b)
        assertNull(backups.find(a))
        assertArrayEquals(byteArrayOf(8), read(b))
        backups.finished(b)
        assertArrayEquals(byteArrayOf(7), read(b))
        backups.move(a, b) // nothing to move: does nothing
        assertArrayEquals(byteArrayOf(7), read(b))
    }

    @Test
    fun `deletes both copies, and reading a missing one throws`() {
        backups.keep(a, byteArrayOf(1))
        backups.keepUnfinished(a, byteArrayOf(2))
        val unfinished = backups.find(a)!!
        backups.delete(a)
        assertNull(backups.find(a))
        assertThrows<IOException> { backups.read(a, unfinished) }
    }

    @Test
    fun `keeps only the newest few databases, always the one just saved, with both its copies`() {
        val locations = (1..Backups.MAX_DATABASES + 2).map { "content://provider/document/$it" }
        locations.forEachIndexed { i, location ->
            backups.keep(location, byteArrayOf(i.toByte()))
            backups.keepUnfinished(location, byteArrayOf(i.toByte()))
            // Older on disk than the ones after it, whatever the file system's clock resolution.
            dir.listFiles()!!.forEach { it.setLastModified(it.lastModified() - 10_000) }
        }
        val kept = locations.filter { backups.find(it) != null }
        assertEquals(locations.takeLast(Backups.MAX_DATABASES), kept)
        assertEquals(Backups.MAX_DATABASES * 2, dir.list()!!.size)
    }
}
