package de.reitano.keypassmobile.prototype.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KdbxProbeTest {

    private fun open(name: String, password: String = TestDbs.PASSWORD, argon2: Argon2Function? = null) =
        KdbxProbe.open(TestDbs.bytes(name), TestDbs.password(password), argon2)

    private fun OpenedDatabase.entry(title: String) =
        assertNotNull(entries.singleOrNull { it.title == title }, "Eintrag fehlt: $title")

    private fun printStats(label: String, s: OpenStats) = println(
        "[$label] KDBX ${s.formatVersion}, ${s.cipher}, ${s.kdf.describe()}, ${s.kdfImplementation}: " +
            "KDF %.1f ms, decode %.1f ms, Liste %.1f ms, gesamt %.1f ms, %d Einträge, %d Gruppen".format(
                s.kdfMillis, s.decodeMillis, s.indexMillis, s.totalMillis, s.entryCount, s.groupCount,
            ),
    )

    @ParameterizedTest
    @CsvSource(
        "cli-default.kdbx,      3.1, AES-256,  AES,      3,    2",
        "argon2d-aes.kdbx,      4.0, AES-256,  Argon2d,  3,    2",
        "argon2id-aes.kdbx,     4.0, AES-256,  Argon2id, 3,    2",
        "argon2d-chacha20.kdbx, 4.0, ChaCha20, Argon2d,  3,    2",
        "argon2d-twofish.kdbx,  4.0, Twofish,  Argon2d,  3,    2",
        "kdbx41-features.kdbx,  4.1, AES-256,  Argon2d,  3,    2",
        "large-3000.kdbx,       4.0, AES-256,  Argon2d,  3000, 11",
    )
    fun `opens every variant`(file: String, version: String, cipher: String, kdf: String, entries: Int, groups: Int) {
        val db = open(file)
        val s = db.stats
        printStats(file, s)
        assertEquals(version, s.formatVersion)
        assertEquals(cipher, s.cipher)
        when (val info = s.kdf) {
            is KdfInfo.AesKdf -> {
                assertEquals("AES", kdf)
                assertEquals(1_000_000, info.rounds)
            }
            is KdfInfo.Argon2 -> {
                assertEquals(kdf, info.variant.name)
                assertEquals(16 * 1024, info.memoryKiB)
                assertEquals(2, info.iterations)
                assertEquals(2, info.parallelism)
                assertEquals(0x13, info.version)
            }
        }
        assertEquals(entries, s.entryCount)
        assertEquals(groups, s.groupCount)
        assertEquals(MeasuringKdfProvider.KOTPASS, s.kdfImplementation)
    }

    @ParameterizedTest
    @CsvSource("cli-default.kdbx", "argon2d-aes.kdbx", "argon2d-chacha20.kdbx", "argon2d-twofish.kdbx")
    fun `basic content is readable`(file: String) {
        val db = open(file)
        val github = db.entry("GitHub")
        assertEquals("kpm-test", github.userName)
        assertEquals("https://github.com/login", github.url)
        assertEquals("Testeintrag", github.notes)
        assertEquals("gh-Test-Passwort-1", db.passwordOf(github.uuid))
        val baker = db.entry("Bäckerei Müller")
        assertEquals("Größe: XL", baker.notes)
        assertEquals("Brötchen-42", db.passwordOf(baker.uuid))
        assertEquals("12345678", db.entry("Sparkasse").userName)
    }

    @ParameterizedTest
    @CsvSource("cli-default.kdbx", "argon2d-aes.kdbx", "kdbx41-features.kdbx")
    fun `wrong password gives a clean error`(file: String) {
        val e = assertFailsWith<KdbxOpenException> { open(file, password = "falsch") }
        assertEquals(KdbxOpenException.Reason.WRONG_KEY, e.reason)
        println("[$file] falsches Passwort: ${e.message}")
    }

    @Test
    fun `kdbx 4_1 fields are readable`() {
        val db = open(TestDbs.KDBX41_FEATURES)
        assertEquals("4.1", db.stats.formatVersion)
        val shop = db.entry("Online-Shop")
        assertEquals("kunde@example.com", shop.userName)
        assertEquals("https://shop.example.com/login", shop.url)
        assertEquals(listOf("https://m.shop.example.com", "https://shop.example.de"), shop.additionalUrls)
        // KeePassXC sorts tags when saving.
        assertEquals(listOf("Einkauf", "Größe", "Wichtig"), shop.tags)
        assertEquals("Erste Zeile\nZweite Zeile mit Umlauten: äöü ÄÖÜ ß", shop.notes)
        assertEquals(mapOf("Kundennummer" to "K-4711"), shop.customFields)
        assertEquals(setOf("PIN"), shop.protectedFieldNames)
        assertEquals("shop-Geheim-123", db.passwordOf(shop.uuid))
        assertEquals(listOf("Privat"), db.entry("Router").tags)
        assertEquals("", db.entry("Nur Benutzername").url)

        assertEquals(listOf("Online-Shop"), db.search("größe").matches.map { it.title })
        assertEquals(listOf("Online-Shop"), db.search("ÄÖÜ").matches.map { it.title })
        assertEquals(listOf("Online-Shop"), db.search("shop.example.de").matches.map { it.title })
        assertEquals(listOf("Router"), db.search("heimnetz 192.168").matches.map { it.title })
    }

    @Test
    fun `keepassxc aes-kdf in kdbx 4 opens`() {
        // Unmodified keepassxc-cli import with 4.1 content. KeePassXC writes the
        // KeePass AES-KDF UUID (c9d9f39a...) also in KDBX 4, which kotpass knows.
        val bytes = TestDbs.bytes(TestDbs.KDBX41_AESKDF_KEEPASSXC)
        assertEquals(UUID.fromString("c9d9f39a-628a-4460-bf74-0d08c18a4fea"), KdbxHeader.kdfUuid(bytes))
        val db = KdbxProbe.open(bytes, TestDbs.password())
        printStats(TestDbs.KDBX41_AESKDF_KEEPASSXC, db.stats)
        assertEquals("4.1", db.stats.formatVersion)
        assertEquals(KdfInfo.AesKdf(7_317_073), db.stats.kdf)
        assertEquals(setOf("Einkauf", "Wichtig", "Größe"), db.entry("Online-Shop").tags.toSet())
    }

    @Test
    fun `header reader finds the kdf uuid`() {
        assertEquals(
            UUID.fromString("9e298b19-56db-4773-b23d-fc3ec6f0a1e6"),
            KdbxHeader.kdfUuid(TestDbs.bytes(TestDbs.ARGON2ID_AES)),
        )
        assertNull(KdbxHeader.kdfUuid(TestDbs.bytes(TestDbs.CLI_DEFAULT)))
        assertNull(KdbxHeader.kdfUuid(ByteArray(5)))
    }

    @Test
    fun `opens with the password hash`() {
        for (file in listOf(TestDbs.ARGON2D_AES, TestDbs.CLI_DEFAULT, TestDbs.KDBX41_FEATURES)) {
            val hash = KdbxProbe.passwordHash(TestDbs.password())
            val db = KdbxProbe.openWithPasswordHash(TestDbs.bytes(file), hash)
            assertTrue(db.stats.entryCount > 0, file)
            assertTrue(hash.all { it == 0.toByte() }, "Hash nicht überschrieben: $file")
        }
        val wrong = KdbxProbe.passwordHash(TestDbs.password("falsch"))
        val e = assertFailsWith<KdbxOpenException> {
            KdbxProbe.openWithPasswordHash(TestDbs.bytes(TestDbs.ARGON2D_AES), wrong)
        }
        assertEquals(KdbxOpenException.Reason.WRONG_KEY, e.reason)
    }

    @Test
    fun `kotpass keeps the same sha-256 internally`() {
        // Credentials.from(passphrase) stores SHA-256(password): the value EN-03 needs.
        val credentials = Credentials.from(EncryptedValue.fromBinary(TestDbs.password()))
        assertContentEquals(KdbxProbe.passwordHash(TestDbs.password()), assertNotNull(credentials.passphrase).getBinary())
    }

    @Test
    fun `password array is overwritten, also on failure`() {
        val ok = TestDbs.password()
        KdbxProbe.open(TestDbs.bytes(TestDbs.ARGON2D_AES), ok).close()
        assertTrue(ok.all { it == 0.toByte() })

        val wrong = TestDbs.password("falsch")
        assertFailsWith<KdbxOpenException> { KdbxProbe.open(TestDbs.bytes(TestDbs.ARGON2D_AES), wrong) }
        assertTrue(wrong.all { it == 0.toByte() })
    }

    @Test
    fun `umlaut password works as utf-8 like in keepassxc`() {
        val db = open(TestDbs.UMLAUT_PASSWORD, password = TestDbs.PASSWORD_UMLAUT)
        assertEquals(TestDbs.BASIC_ENTRY_COUNT, db.stats.entryCount)
        val latin1 = TestDbs.PASSWORD_UMLAUT.toByteArray(Charsets.ISO_8859_1)
        val e = assertFailsWith<KdbxOpenException> {
            KdbxProbe.open(TestDbs.bytes(TestDbs.UMLAUT_PASSWORD), latin1)
        }
        assertEquals(KdbxOpenException.Reason.WRONG_KEY, e.reason)
    }

    @Test
    fun `external argon2 gets the parameters of the file`() {
        var seen: Argon2Params? = null
        var inputSize = -1
        val recording = Argon2Function { password, params ->
            seen = params
            inputSize = password.size
            KotpassArgon2.hash(password, params)
        }
        val db = open(TestDbs.ARGON2ID_AES, argon2 = recording)
        assertEquals(MeasuringKdfProvider.EXTERNAL, db.stats.kdfImplementation)
        val params = assertNotNull(seen)
        assertEquals(Argon2Variant.Argon2id, params.variant)
        assertEquals(16 * 1024, params.memoryKiB)
        assertEquals(2, params.iterations)
        assertEquals(2, params.parallelism)
        assertEquals(0x13, params.version)
        assertEquals(32, params.salt.size)
        assertEquals(false, params.usesSecretOrAssociatedData)
        assertEquals(32, inputSize)
    }

    @Test
    fun `search over 3000 entries`() {
        val db = open(TestDbs.LARGE)
        printStats("large", db.stats)
        assertEquals(3000, db.search("").matches.size)
        assertEquals(600, db.search("wichtig").matches.size)
        assertEquals(429, db.search("Privat").matches.size)
        assertEquals(86, db.search("wichtig privat").matches.size)
        assertEquals(listOf("Konto 0042 Shop"), db.search("konto 0042").matches.map { it.title })
        assertEquals(listOf("Konto 0100 Bank"), db.search("alt100.example.org").matches.map { it.title })
        assertEquals(300, db.search("versicherung").matches.size)
        assertEquals(0, db.search("zzz-kein-treffer").matches.size)

        for (query in listOf("a", "wichtig", "konto 0042", "zzz-kein-treffer")) {
            val t = db.measureSearch(query, repetitions = 20)
            println("[large] Suche '%s': %d Treffer, erster Lauf %.2f ms, Mittel %.3f ms"
                .format(t.query, t.hits, t.firstMillis, t.averageMillis))
        }
        db.close()
        assertEquals(0, db.entries.size)
    }

    @Test
    fun `timing with keepassxc gui default parameters`() {
        // Argon2d, 64 MiB, 100 iterations, parallelism 4: about 0.85 s in KeePassXC on the dev PC.
        repeat(2) { run ->
            val db = open(TestDbs.GUI_DEFAULT)
            printStats("gui-default Lauf ${run + 1}", db.stats)
            val kdf = db.stats.kdf as KdfInfo.Argon2
            assertEquals(Argon2Variant.Argon2d, kdf.variant)
            assertEquals(64 * 1024, kdf.memoryKiB)
            assertEquals(100, kdf.iterations)
            assertEquals(4, kdf.parallelism)
        }
    }
}
