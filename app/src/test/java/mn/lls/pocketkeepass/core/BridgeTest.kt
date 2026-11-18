package mn.lls.pocketkeepass.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Calls the Rust engine from Kotlin through the generated bindings, on this computer.
 *
 * The engine's own behavior is tested in Rust (`core/`). These tests only prove that the bridge
 * carries every kind of value across correctly: bytes, optional values, records, errors, and
 * object lifetimes. They use the sample databases in `core/tests/fixtures`.
 */
class BridgeTest {
    private val password get() = "test-password".toByteArray()

    private fun fixture(name: String): ByteArray =
        File(System.getProperty("pocketkeepass.fixtures"), name).readBytes()

    @Test
    fun `the engine library loads`() {
        assertEquals("0.1.0", engineVersion())
    }

    @Test
    fun `a database opens with its password and describes itself`() {
        UnlockedDatabase.open(fixture("kdbx41-keepassxc.kdbx"), password, null).use { db ->
            val summary = db.summary()
            assertEquals("Passwords", summary.name)
            assertEquals("4.1", summary.formatVersion)
            assertFalse(summary.needsUpgrade)
            assertEquals(1u, summary.groupCount)
            assertEquals(3u, summary.entryCount)
        }
    }

    @Test
    fun `a database opens with a key file alone, and with password plus key file`() {
        UnlockedDatabase.open(fixture("keyfile-only.kdbx"), null, fixture("keyfile.keyx")).use { db ->
            assertEquals(1u, db.summary().entryCount)
        }
        val keyFile = fixture("kdbx4-hex.key")
        UnlockedDatabase.open(fixture("kdbx4-argon2d-aes256-keyfile.kdbx"), password, keyFile).use { db ->
            assertEquals(2u, db.summary().entryCount)
        }
    }

    @Test
    fun `the caller's password bytes are left alone for the caller to wipe`() {
        val mine = password
        UnlockedDatabase.open(fixture("pw-only.kdbx"), mine, null).close()
        assertArrayEquals(password, mine)
    }

    @Test
    fun `each kind of failure arrives as its own exception with a readable message`() {
        val wrongKey = assertThrows(KeePassException.WrongKey::class.java) {
            UnlockedDatabase.open(fixture("pw-only.kdbx"), "nope".toByteArray(), null)
        }
        assertEquals("The password or key file is incorrect.", wrongKey.message)

        assertThrows(KeePassException.NotKeePass::class.java) {
            UnlockedDatabase.open("not a database".toByteArray(), password, null)
        }
        assertThrows(KeePassException.Corrupt::class.java) {
            UnlockedDatabase.open(fixture("kdbx4-argon2id-chacha20.kdbx").copyOf(500), password, null)
        }
        assertThrows(KeePassException.InvalidKeyFile::class.java) {
            UnlockedDatabase.open(fixture("pw-only.kdbx"), null, null)
        }
    }

    @Test
    fun `a saved database opens again with the same content`() {
        UnlockedDatabase.open(fixture("kdbx4-argon2id-chacha20.kdbx"), password, null).use { db ->
            val saved = db.save()
            assertFalse(saved.contentEquals(fixture("kdbx4-argon2id-chacha20.kdbx")))
            UnlockedDatabase.open(saved, password, null).use { reopened ->
                assertEquals(db.summary(), reopened.summary())
            }
        }
    }

    @Test
    fun `a KDBX 3 database is only saved after an upgrade`() {
        UnlockedDatabase.open(fixture("kdbx3-attachment.kdbx"), password, null).use { db ->
            assertTrue(db.summary().needsUpgrade)
            assertThrows(KeePassException.NeedsUpgrade::class.java) { db.save() }

            db.upgradeToKdbx4()
            assertFalse(db.summary().needsUpgrade)
            UnlockedDatabase.open(db.save(), password, null).use { reopened ->
                assertEquals("4.0", reopened.summary().formatVersion)
                assertEquals(2u, reopened.summary().entryCount)
            }
        }
    }

    @Test
    fun `groups and search results arrive as nested records`() {
        UnlockedDatabase.open(fixture("browse.kdbx"), password, null).use { db ->
            val root = db.listGroup(null)
            assertTrue(root.isRoot)
            assertEquals(listOf("Work", "Banking", "Archive", "Recycle Bin"), root.groups.map { it.name })
            assertTrue(root.groups.last().isRecycleBin)
            assertEquals(EntryItem("02".repeat(16), "Email", "me@example.com", "https://mail.example.com"), root.entries.single())

            val work = db.listGroup(root.groups.first().id)
            assertEquals(listOf("Root"), work.parents.map { it.name })
            assertEquals(GroupItem("05".repeat(16), "Servers", 0u, 1u, isRecycleBin = false), work.groups.single())

            val hit = db.search("rack 4").single()
            assertEquals("Database Server", hit.entry.title)
            assertEquals(listOf("Work", "Servers"), hit.groupPath)
            assertTrue(db.search("hunter2").isEmpty())

            assertThrows(KeePassException.NotFound::class.java) { db.listGroup("ee".repeat(16)) }
        }
    }

    @Test
    fun `an entry arrives without protected values, which come one at a time as bytes`() {
        UnlockedDatabase.open(fixture("browse.kdbx"), password, null).use { db ->
            val bank = db.entry("08".repeat(16))
            assertEquals("Crédit Union", bank.title)
            assertEquals(listOf("Banking"), bank.groupPath)
            assertEquals(listOf("UserName", "Password", "URL", "Notes"), bank.fields.map { it.key })
            assertEquals(EntryField("UserName", "", isProtected = true, isEmpty = false), bank.fields[0])
            assertEquals(EntryField("URL", "https://cu.example.com", isProtected = false, isEmpty = false), bank.fields[2])

            assertArrayEquals("jo-secret-user".toByteArray(), db.revealField(bank.id, "UserName"))
            assertArrayEquals("p4ss".toByteArray(), db.revealField(bank.id, "Password"))
            assertThrows(KeePassException.NotFound::class.java) { db.revealField(bank.id, "Nope") }
            assertThrows(KeePassException.NotFound::class.java) { db.entry("ee".repeat(16)) }
        }
    }

    @Test
    fun `the key hash crosses as bytes and opens the database again`() {
        val file = fixture("kdbx41-keepassxc.kdbx")
        val hash = UnlockedDatabase.open(file, password, null).use { it.keyHash() }
        assertEquals(32, hash.size)
        UnlockedDatabase.openWithKeyHash(file, hash).use { db ->
            assertEquals(3u, db.summary().entryCount)
        }
        assertThrows(KeePassException.WrongKey::class.java) { UnlockedDatabase.openWithKeyHash(file, ByteArray(32)) }
    }

    @Test
    fun `a locked database refuses everything`() {
        val db = UnlockedDatabase.open(fixture("pw-only.kdbx"), password, null)
        assertFalse(db.isLocked())
        db.lock()
        assertTrue(db.isLocked())
        assertThrows(KeePassException.Locked::class.java) { db.summary() }
        assertThrows(KeePassException.Locked::class.java) { db.save() }
        assertThrows(KeePassException.Locked::class.java) { db.listGroup(null) }
        assertThrows(KeePassException.Locked::class.java) { db.search("email") }
        assertThrows(KeePassException.Locked::class.java) { db.entry("02".repeat(16)) }
        assertThrows(KeePassException.Locked::class.java) { db.revealField("02".repeat(16), "Password") }
        db.close()
    }
}
