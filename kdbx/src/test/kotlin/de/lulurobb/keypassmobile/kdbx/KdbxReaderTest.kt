package de.lulurobb.keypassmobile.kdbx

import de.lulurobb.keypassmobile.kdbx.KdbxOpenException.Reason
import de.lulurobb.keypassmobile.model.Vault
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KdbxReaderTest {

    @TempDir
    lateinit var tempDir: File

    private val reader = KdbxReader()

    private fun open(name: String, password: String = TestDbs.PASSWORD): Vault =
        reader.open(TestDbs.file(name), TestDbs.hash(password))

    private fun Vault.entry(title: String) =
        assertNotNull(entries.singleOrNull { it.title == title }, "Eintrag fehlt: $title")

    private fun Vault.password(title: String) = String(revealPassword(entry(title).id))

    private fun write(name: String, bytes: ByteArray) = File(tempDir, name).also { it.writeBytes(bytes) }

    private fun expectFailure(reason: Reason, file: File, hash: ByteArray = TestDbs.hash()): KdbxOpenException {
        val e = assertFailsWith<KdbxOpenException> { reader.open(file, hash) }
        assertEquals(reason, e.reason, "Grund für ${file.name}: ${e.message}")
        assertNoSecrets(e)
        return e
    }

    /** Messages of the exception and its cause chain contain no test secret and no library text. */
    private fun assertNoSecrets(e: KdbxOpenException) {
        var t: Throwable? = e
        while (t != null) {
            val message = t.message.orEmpty()
            for (secret in listOf(TestDbs.PASSWORD, "gh-Test-Passwort-1", "Brötchen-42")) {
                assertFalse(message.contains(secret), "Geheimnis in Meldung: ${t.javaClass.name}")
            }
            if (t !== e) assertIs<SanitizedException>(t)
            t = t.cause
        }
    }

    @ParameterizedTest
    @CsvSource(
        // file,                      KDBX, cipher,   KDF,      entries, root group
        "cli-default.kdbx,             3.1, AES-256,  AES-KDF,  3,    Root",
        "argon2d-aes.kdbx,             4.0, AES-256,  Argon2d,  3,    Root",
        "argon2id-aes.kdbx,            4.0, AES-256,  Argon2id, 3,    Root",
        "argon2d-chacha20.kdbx,        4.0, ChaCha20, Argon2d,  3,    Root",
        "argon2d-twofish.kdbx,         4.0, Twofish,  Argon2d,  3,    Root",
        "kdbx41-features.kdbx,         4.1, AES-256,  Argon2d,  3,    Root",
        "kdbx41-aeskdf-keepassxc.kdbx, 4.1, AES-256,  AES-KDF,  3,    Root",
        "keepassxc-structure.kdbx,     4.1, AES-256,  AES-KDF,  8,    Tresor",
        "large-3000.kdbx,              4.0, AES-256,  Argon2d,  3000, Root",
        "gui-default-argon2d.kdbx,     4.0, AES-256,  Argon2d,  3,    Root",
    )
    fun `opens every test database`(file: String, version: String, cipher: String, kdf: String, entries: Int, root: String) {
        val header = assertIs<KdbxHeader.Inspection.Kdbx>(KdbxHeader.inspect(TestDbs.bytes(file)))
        assertEquals(version, "${header.major}.${header.minor}")
        val cipherIds = mapOf(
            "AES-256" to KdbxHeader.CIPHER_AES256,
            "ChaCha20" to KdbxHeader.CIPHER_CHACHA20,
            "Twofish" to KdbxHeader.CIPHER_TWOFISH,
        )
        assertEquals(cipherIds.getValue(cipher), header.cipherId)
        val kdfIds = mapOf(
            "AES-KDF" to KdbxHeader.KDF_AES,
            "Argon2d" to KdbxHeader.KDF_ARGON2D,
            "Argon2id" to KdbxHeader.KDF_ARGON2ID,
        )
        // KDBX 3.1 has no KDF parameters field (always AES-KDF).
        assertEquals(if (header.major >= 4) kdfIds.getValue(kdf) else null, header.kdfId)

        val start = System.nanoTime()
        val vault = open(file)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        println("[%s] KDBX %s, %s, %s: geöffnet in %.0f ms, %d Einträge".format(file, version, cipher, kdf, millis, vault.entries.size))
        assertEquals(entries, vault.entries.size)
        assertEquals(root, vault.rootGroupName)
        vault.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["cli-default.kdbx", "argon2d-aes.kdbx", "argon2d-chacha20.kdbx", "argon2d-twofish.kdbx"])
    fun `basic content is readable`(file: String) {
        val vault = open(file)
        val github = vault.entry("GitHub")
        assertEquals("kpm-test", github.username)
        assertEquals("https://github.com/login", github.url)
        assertEquals("Testeintrag", github.notes)
        assertEquals(emptyList(), github.groupPath)
        assertEquals("gh-Test-Passwort-1", vault.password("GitHub"))
        val baker = vault.entry("Bäckerei Müller")
        assertEquals("Größe: XL", baker.notes)
        assertEquals("Brötchen-42", vault.password("Bäckerei Müller"))
        val bank = vault.entry("Sparkasse")
        assertEquals("12345678", bank.username)
        assertEquals(listOf("Banken"), bank.groupPath)
    }

    @Test
    fun `kdbx 4_1 fields are readable`() {
        for (file in listOf(TestDbs.KDBX41_FEATURES, TestDbs.KDBX41_AESKDF_KEEPASSXC)) {
            val vault = open(file)
            val shop = vault.entry("Online-Shop")
            assertEquals("kunde@example.com", shop.username)
            assertEquals("https://shop.example.com/login", shop.url)
            assertEquals(listOf("https://m.shop.example.com", "https://shop.example.de"), shop.additionalUrls)
            assertEquals(listOf("Einkauf", "Größe", "Wichtig"), shop.tags.sorted())
            assertEquals("Erste Zeile\nZweite Zeile mit Umlauten: äöü ÄÖÜ ß", shop.notes)
            assertEquals(listOf("Einkaufen"), shop.groupPath)
            assertEquals("shop-Geheim-123", vault.password("Online-Shop"))
            // Named custom icon of KDBX 4.1, written by KeePassXC.
            val iconId = assertNotNull(shop.customIconId)
            assertEquals(UUID(0L, 0x20L), iconId)
            assertEquals(setOf(iconId), vault.customIcons.keys)
            assertEquals(listOf("Privat"), vault.entry("Router").tags)
            assertEquals("", vault.entry("Nur Benutzername").url)
            assertEquals("", vault.password("Nur Benutzername"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["cli-default.kdbx", "argon2d-aes.kdbx", "argon2d-chacha20.kdbx", "argon2d-twofish.kdbx", "kdbx41-features.kdbx"])
    fun `wrong password gives WRONG_KEY`(file: String) {
        val e = expectFailure(Reason.WRONG_KEY, TestDbs.file(file), TestDbs.hash("falsch"))
        println("[$file] falsches Passwort: ${e.message}")
    }

    @Test
    fun `wrong key is always WRONG_KEY, also for kdbx 3_1`() {
        // KDBX 3.1 has no header HMAC; a wrong key could also surface as padding
        // or stream errors. Try many random keys.
        val random = java.util.Random(4711)
        for (file in listOf(TestDbs.CLI_DEFAULT, TestDbs.ARGON2D_TWOFISH)) {
            repeat(20) {
                val hash = ByteArray(32).also(random::nextBytes)
                expectFailure(Reason.WRONG_KEY, TestDbs.file(file), hash)
            }
        }
    }

    @Test
    fun `the caller's hash is neither modified nor required afterwards`() {
        val hash = TestDbs.hash()
        val copy = hash.copyOf()
        val vault = reader.open(TestDbs.file(TestDbs.ARGON2D_AES), hash)
        assertContentEquals(copy, hash)
        hash.fill(0)
        assertEquals("gh-Test-Passwort-1", vault.password("GitHub"))

        val wrong = TestDbs.hash("falsch")
        val wrongCopy = wrong.copyOf()
        assertFailsWith<KdbxOpenException> { reader.open(TestDbs.file(TestDbs.ARGON2D_AES), wrong) }
        assertContentEquals(wrongCopy, wrong)
    }

    @Test
    fun `hash must have 32 bytes`() {
        assertFailsWith<IllegalArgumentException> { reader.open(TestDbs.file(TestDbs.ARGON2D_AES), ByteArray(31)) }
        // The plain UTF-8 password is not accepted by mistake.
        assertFailsWith<IllegalArgumentException> {
            reader.open(TestDbs.file(TestDbs.ARGON2D_AES), TestDbs.PASSWORD.toByteArray())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["cli-default.kdbx", "argon2d-aes.kdbx", "kdbx41-features.kdbx", "large-3000.kdbx"])
    fun `truncated file gives CORRUPT`(file: String) {
        val bytes = TestDbs.bytes(file)
        val header = assertIs<KdbxHeader.Inspection.Kdbx>(KdbxHeader.inspect(bytes))
        val headerLength = assertNotNull(header.headerLength)
        var tested = 0
        for (length in (listOf(13, 40, 120, headerLength) + (bytes.size / 2..bytes.size / 2 + 15) + listOf(bytes.size - 40, bytes.size - 1))) {
            // KDBX 3.1 cut at a cipher block boundary: see the next test.
            val payload = length - headerLength
            if (header.major < 4 && payload > 0 && payload % 16 == 0) continue
            val e = expectFailure(Reason.CORRUPT, write("cut-$length-$file", bytes.copyOf(length)))
            if (length == bytes.size / 2) println("[$file] abgeschnitten: ${e.message} (${e.cause?.message})")
            tested++
        }
        assertTrue(tested >= 20)
    }

    @Test
    fun `kdbx 3_1 cut at a block boundary cannot always be told from a wrong key`() {
        // Known limitation (KdbxHeader.isTruncatedKdbx3): KDBX 3.1 has no header
        // MAC and kotpass checks the key only after decrypting everything.
        val bytes = TestDbs.bytes(TestDbs.CLI_DEFAULT)
        val header = assertIs<KdbxHeader.Inspection.Kdbx>(KdbxHeader.inspect(bytes))
        val headerLength = assertNotNull(header.headerLength)
        val blockCount = (bytes.size - headerLength) / 16
        val reasons = (1 until blockCount step blockCount / 10).map { blocks ->
            val e = assertFailsWith<KdbxOpenException> {
                reader.open(write("aligned-$blocks.kdbx", bytes.copyOf(headerLength + blocks * 16)), TestDbs.hash())
            }
            e.reason
        }
        println("[cli-default.kdbx] an Blockgrenzen abgeschnitten: ${reasons.groupingBy { it }.eachCount()}")
        assertTrue(reasons.all { it == Reason.WRONG_KEY || it == Reason.CORRUPT })
    }

    @Test
    fun `damaged payload gives CORRUPT`() {
        for (file in listOf(TestDbs.ARGON2D_AES, TestDbs.LARGE)) {
            val bytes = TestDbs.bytes(file)
            val position = bytes.size - 300
            bytes[position] = (bytes[position].toInt() xor 0x55).toByte()
            val e = expectFailure(Reason.CORRUPT, write("flip-$file", bytes))
            println("[$file] Nutzdaten beschädigt: ${e.message} (${e.cause?.message})")
        }
    }

    @Test
    fun `garbage gives CORRUPT`() {
        val random = ByteArray(4096).also { java.util.Random(1).nextBytes(it) }
        expectFailure(Reason.CORRUPT, write("random.kdbx", random))
        expectFailure(Reason.CORRUPT, write("empty.kdbx", ByteArray(0)))
        expectFailure(Reason.CORRUPT, write("html.kdbx", "<html><body>Fehler 404</body></html>".toByteArray()))
        // Valid signatures, then garbage.
        val header = TestDbs.bytes(TestDbs.ARGON2D_AES).copyOf(12)
        expectFailure(Reason.CORRUPT, write("signature-only.kdbx", header + random))
    }

    @Test
    fun `other keepass formats and versions give UNSUPPORTED`() {
        val kdb = TestDbs.bytes(TestDbs.ARGON2D_AES).also { it[4] = 0x65 } // KeePass 1.x signature
        val e1 = expectFailure(Reason.UNSUPPORTED, write("keepass1.kdb", kdb))
        println("KeePass 1: ${e1.message}")

        val v5 = TestDbs.bytes(TestDbs.ARGON2D_AES).also { it[10] = 5 } // major version 5
        val e2 = expectFailure(Reason.UNSUPPORTED, write("kdbx5.kdbx", v5))
        println("KDBX 5: ${e2.message}")

        // Unknown cipher id: the header field 2 follows the 12 signature bytes.
        val cipher = TestDbs.bytes(TestDbs.ARGON2D_AES)
        assertEquals(2, cipher[12].toInt())
        cipher[17] = (cipher[17].toInt() xor 0x01).toByte()
        val e3 = expectFailure(Reason.UNSUPPORTED, write("cipher.kdbx", cipher))
        println("Unbekannte Verschlüsselung: ${e3.message}")
    }

    @Test
    fun `missing file gives OTHER`() {
        expectFailure(Reason.OTHER, File(tempDir, "fehlt.kdbx"))
    }

    @Test
    fun `signature check`() {
        for (name in listOf(
            TestDbs.CLI_DEFAULT, TestDbs.ARGON2D_AES, TestDbs.ARGON2ID_AES, TestDbs.ARGON2D_CHACHA20,
            TestDbs.ARGON2D_TWOFISH, TestDbs.KDBX41_FEATURES, TestDbs.KDBX41_AESKDF_KEEPASSXC,
            TestDbs.STRUCTURE, TestDbs.LARGE, TestDbs.GUI_DEFAULT, TestDbs.UMLAUT_PASSWORD,
        )) {
            assertTrue(looksLikeKdbx(TestDbs.file(name)), name)
        }
        val valid = TestDbs.bytes(TestDbs.ARGON2D_AES)
        assertTrue(looksLikeKdbx(write("truncated-header.kdbx", valid.copyOf(12))))
        assertFalse(looksLikeKdbx(write("short.kdbx", valid.copyOf(11))))
        assertFalse(looksLikeKdbx(write("empty.kdbx", ByteArray(0))))
        assertFalse(looksLikeKdbx(write("html.kdbx", "<html><body>Fehler</body></html>".toByteArray())))
        assertFalse(looksLikeKdbx(write("keepass1.kdb", valid.copyOf().also { it[4] = 0x65 })))
        assertFalse(looksLikeKdbx(File(tempDir, "fehlt.kdbx")))
        assertFalse(looksLikeKdbx(tempDir))
    }

    @Test
    fun `header reader handles both header formats`() {
        val v3 = assertIs<KdbxHeader.Inspection.Kdbx>(KdbxHeader.inspect(TestDbs.bytes(TestDbs.CLI_DEFAULT)))
        assertEquals(3, v3.major)
        assertEquals(KdbxHeader.CIPHER_AES256, v3.cipherId)
        val v4 = assertIs<KdbxHeader.Inspection.Kdbx>(KdbxHeader.inspect(TestDbs.bytes(TestDbs.ARGON2ID_AES)))
        assertEquals(KdbxHeader.KDF_ARGON2ID, v4.kdfId)
        assertEquals(KdbxHeader.Inspection.NotKeePass, KdbxHeader.inspect(ByteArray(5)))
        assertTrue(assertNotNull(v4.headerLength) in 100..400)
        val cut = assertIs<KdbxHeader.Inspection.Kdbx>(KdbxHeader.inspect(TestDbs.bytes(TestDbs.ARGON2ID_AES).copyOf(40)))
        assertEquals(KdbxHeader.CIPHER_AES256, cut.cipherId)
        assertEquals(null, cut.kdfId)
        assertEquals(null, cut.headerLength)
    }

    @Test
    fun `timing for 3000 entries`() {
        val bytes = TestDbs.bytes(TestDbs.LARGE)
        val hash = TestDbs.hash()
        repeat(3) { run ->
            val t0 = System.nanoTime()
            val database = reader.decode(bytes, hash)
            val t1 = System.nanoTime()
            val content = VaultMapper.map(database.content)
            val t2 = System.nanoTime()
            val vault = reader.open(TestDbs.file(TestDbs.LARGE), hash)
            val t3 = System.nanoTime()
            println(
                "[large-3000 Lauf ${run + 1}] kotpass decode %.1f ms, Abbildung %.1f ms, open() gesamt %.1f ms, %d Einträge"
                    .format((t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, content.entries.size),
            )
            assertEquals(3000, vault.entries.size)
            vault.close()
        }
    }

    @Test
    fun `sanitized cause keeps types but drops messages`() {
        val original = IllegalStateException("geheim", java.io.IOException("auch geheim"))
        val sanitized = SanitizedException(original)
        assertEquals(IllegalStateException::class.java.name, sanitized.message)
        assertEquals(java.io.IOException::class.java.name, sanitized.cause?.message)
        assertContentEquals(original.stackTrace, sanitized.stackTrace)
    }

    @Test
    fun `password hash equals the composite key component kotpass derives from the password`() {
        val fromKotpass = app.keemobile.kotpass.database.Credentials.from(
            app.keemobile.kotpass.cryptography.EncryptedValue.fromString(TestDbs.PASSWORD),
        )
        assertContentEquals(
            MessageDigest.getInstance("SHA-256").digest(TestDbs.PASSWORD.toByteArray()),
            assertNotNull(fromKotpass.passphrase).getBinary(),
        )
        assertContentEquals(assertNotNull(fromKotpass.passphrase).getBinary(), TestDbs.hash())
    }
}
