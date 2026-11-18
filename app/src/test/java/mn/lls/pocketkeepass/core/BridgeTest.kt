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
    fun `the generator makes passwords as bytes, rates them, and refuses impossible options`() {
        val options = PasswordOptions(
            length = 24u,
            lowercase = true,
            uppercase = true,
            digits = true,
            symbols = Symbols.COMMON,
            avoidLookalikes = true,
        )
        val allowed = ('a'..'z') + ('A'..'Z') + ('0'..'9') + passwordSymbols(Symbols.COMMON).toList() -
            passwordLookalikes().toSet()
        val password = generatePassword(options)
        assertEquals(24, password.size)
        assertTrue(password.all { it.toInt().toChar() in allowed })
        assertTrue(passwordStrengthBits(options) > 100.0)
        assertEquals("", passwordSymbols(Symbols.NONE))

        val nothing = options.copy(lowercase = false, uppercase = false, digits = false, symbols = Symbols.NONE)
        val e = assertThrows(KeePassException.Invalid::class.java) { generatePassword(nothing) }
        assertEquals("Choose at least one kind of character.", e.message)
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
    fun `autofill targets cross as an enum with values, and matches come back as records`() {
        UnlockedDatabase.open(fixture("autofill.kdbx"), password, null).use { db ->
            val web = db.autofillMatches(FillTarget.Web("login.example.com", plainHttp = false))
            assertEquals(listOf("Example Login", "Example"), web.map { it.entry.title })
            assertEquals(listOf("Sites"), web[0].groupPath)
            assertEquals("", web[1].entry.username) // protected, so a suggestion can't show it
            assertTrue(db.autofillMatches(FillTarget.Web("login.example.com", plainHttp = true)).isEmpty())

            val app = db.autofillMatches(FillTarget.App("com.example.app"))
            assertEquals(listOf("App Link"), app.map { it.entry.title })
            assertArrayEquals("app-pw".toByteArray(), db.revealField(app[0].entry.id, "Password"))
            assertTrue(db.autofillMatches(FillTarget.App("com.example.other")).isEmpty())
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
    fun `edits cross the bridge, with a null value keeping a protected field unfetched`() {
        UnlockedDatabase.open(fixture("kdbx41-keepassxc.kdbx"), password, null).use { db ->
            val root = db.listGroup(null).group.id
            val pin = FieldEdit("PIN", "0000".toByteArray(), previousKey = null, isProtected = true)
            val id = db.createEntry(
                root,
                listOf(FieldEdit("Title", "Router".toByteArray(), null, false), FieldEdit("Password", "pw".toByteArray(), null, false), pin),
                listOf("home"),
            )
            // The caller's bytes are left for the caller to wipe.
            assertArrayEquals("0000".toByteArray(), pin.value)

            val changed = db.updateEntry(
                id,
                listOf(
                    FieldEdit("Title", "Modem".toByteArray(), null, false),
                    FieldEdit("Password", null, null, false),
                    FieldEdit("Code", null, previousKey = "PIN", isProtected = true),
                ),
                emptyList(),
            )
            assertTrue(changed)
            val entry = db.entry(id)
            assertEquals("Modem", entry.title)
            assertEquals(listOf("UserName", "Password", "URL", "Code", "Notes"), entry.fields.map { it.key })
            assertArrayEquals("pw".toByteArray(), db.revealField(id, "Password"))
            assertArrayEquals("0000".toByteArray(), db.revealField(id, "Code"))

            val bad = FieldEdit("Title", "bell\u0007".toByteArray(), null, false)
            val refused = assertThrows(KeePassException.Invalid::class.java) { db.updateEntry(id, listOf(bad), emptyList()) }
            assertTrue(refused.message.orEmpty().contains("control character"), refused.message)

            val group = db.createGroup(root, "Made", "")
            assertTrue(db.moveEntry(id, group))
            assertEquals(1u, db.listGroup(group).group.entryCount)
            assertEquals(Deletion.MOVED_TO_RECYCLE_BIN, db.deleteGroup(group))
            assertEquals(listOf(0u, 1u), db.allGroups().map { it.depth }.distinct().take(2))

            val reopened = UnlockedDatabase.open(db.save(), password, null)
            reopened.use { assertEquals("Modem", it.entry(id).title) }
        }
    }

    @Test
    fun `TOTP settings cross as a sealed class, and codes as bytes`() {
        UnlockedDatabase.open(fixture("totp.kdbx"), password, null).use { db ->
            val ready = db.entry("21".repeat(16)).totp
            assertEquals(TotpStatus.Ready(periodSeconds = 30u, length = 6u, fields = listOf("otp")), ready)
            // The RFC 4226 code for counter 1, which a 30-second period reaches at 30 to 59.
            assertArrayEquals("287082".toByteArray(), db.totpCode("21".repeat(16), 59uL))

            val broken = db.entry("2c".repeat(16)).totp
            assertTrue(broken is TotpStatus.Invalid && "Base32" in broken.reason)
            assertThrows(KeePassException.Invalid::class.java) { db.totpCode("2c".repeat(16), 59uL) }
            assertEquals(TotpStatus.None, db.entry("2e".repeat(16)).totp)
            assertThrows(KeePassException.NotFound::class.java) { db.totpCode("2e".repeat(16), 59uL) }
        }
    }

    @Test
    fun `passkeys cross as records and sealed classes, and requests and responses as JSON`() {
        UnlockedDatabase.open(fixture("passkey.kdbx"), password, null).use { db ->
            val browser = PasskeyClient.Browser(clientDataHash = ByteArray(32) { 0x5a })
            val app = PasskeyClient.App("com.example.app", listOf(ByteArray(32) { 0xaa.toByte() }))
            val signIn = """{"rpId":"app.example","challenge":"AQID"}"""

            val choices = db.passkeysFor(signIn, app)
            assertEquals(1, choices.size)
            assertEquals("Linked App", choices[0].result.entry.title)
            assertEquals("app-user", choices[0].username)
            assertEquals(null, choices[0].unusableReason)
            assertEquals(AppTrust.LINKED, choices[0].trust)
            assertEquals(AppTrust.BROWSER, db.passkeysFor(signIn, browser)[0].trust)

            val response = db.assertPasskey(choices[0].result.entry.id, signIn, app)
            assertTrue(response.contains("\"signature\""))

            assertEquals(null, db.entry("35".repeat(16)).passkey!!.unusableReason) // Ed25519
            val mlDsa = db.entry("3b".repeat(16)).passkey!!
            assertEquals("mldsa.example", mlDsa.rpId)
            assertTrue(mlDsa.unusableReason!!.contains("ML-DSA"))
            assertTrue("KPEX_PASSKEY_PRIVATE_KEY_PEM" in mlDsa.fields)
            assertEquals(null, db.entry("37".repeat(16)).passkey)

            val create = """{"rp":{"id":"new.example","name":"New"},"user":{"id":"dQ","name":"u","displayName":"U"},""" +
                """"challenge":"AQ","pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""
            assertEquals("new.example", db.passkeyCreation(create).rpId)
            val created = db.createPasskey(PasskeyTarget.NewEntry(groupId = "01".repeat(16)), create, browser)
            assertTrue(created.responseJson.contains("\"attestationObject\""))
            assertTrue(db.undoPasskey(created.entryId))
            assertThrows(KeePassException.NotFound::class.java) { db.entry(created.entryId) }

            // The fixture's "Example (Passkey)" has credential ID 0x31 × 32, "MTEx…" in base64url.
            val duplicate = create.replace("new.example", "example.com").replace(
                "\"pubKeyCredParams\"",
                "\"excludeCredentials\":[{\"type\":\"public-key\",\"id\":\"${"MTEx".repeat(10)}MTE\"}],\"pubKeyCredParams\"",
            )
            assertThrows(KeePassException.PasskeyExists::class.java) { db.passkeyCreation(duplicate) }
            assertThrows(KeePassException.PasskeyUnsupported::class.java) {
                db.passkeyCreation(create.replace("-7", "-8"))
            }
            assertThrows(KeePassException.Invalid::class.java) { db.assertPasskey("37".repeat(16), signIn, browser) }
        }
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
        assertThrows(KeePassException.Locked::class.java) { db.totpCode("02".repeat(16), 59uL) }
        assertThrows(KeePassException.Locked::class.java) { db.createGroup("02".repeat(16), "x", "") }
        assertThrows(KeePassException.Locked::class.java) { db.deleteEntry("02".repeat(16)) }
        db.close()
    }
}
