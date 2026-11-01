package de.lulurobb.keypassmobile.kdbx

import de.lulurobb.keypassmobile.kdbx.KdbxOpenException.Reason
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Argon2EngineTest {

    private class RecordingEngine(private val delegate: Argon2Engine = KotpassArgon2Engine) : Argon2Engine {
        var calls = 0
        var params: Argon2Params? = null
        var passwordSize = -1
        override fun hash(password: ByteArray, params: Argon2Params): ByteArray {
            calls++
            this.params = params
            passwordSize = password.size
            return delegate.hash(password, params)
        }
    }

    @Test
    fun `engine gets the parameters of the file`() {
        for ((file, variant) in listOf(TestDbs.ARGON2ID_AES to Argon2Variant.Argon2id, TestDbs.ARGON2D_CHACHA20 to Argon2Variant.Argon2d)) {
            val engine = RecordingEngine()
            val vault = KdbxReader(engine).open(TestDbs.file(file), TestDbs.hash())
            assertEquals("gh-Test-Passwort-1", String(vault.revealPassword(vault.entries.single { it.title == "GitHub" }.id)))
            assertEquals(1, engine.calls)
            val params = assertNotNull(engine.params)
            assertEquals(variant, params.variant)
            assertEquals(16 * 1024L, params.memoryKiB)
            assertEquals(2L, params.iterations)
            assertEquals(2, params.parallelism)
            assertEquals(0x13, params.version)
            assertEquals(32, params.salt.size)
            assertFalse(params.usesSecretOrAssociatedData)
            assertEquals(32, engine.passwordSize)
        }
    }

    @Test
    fun `gui default parameters reach the engine`() {
        // Do not compute the real 64 MiB hash here; the wrong result gives WRONG_KEY.
        var seen: Argon2Params? = null
        val engine = Argon2Engine { _, params -> seen = params; ByteArray(32) }
        val e = assertFailsWith<KdbxOpenException> { KdbxReader(engine).open(TestDbs.file(TestDbs.GUI_DEFAULT), TestDbs.hash()) }
        assertEquals(Reason.WRONG_KEY, e.reason)
        val params = assertNotNull(seen)
        assertEquals(Argon2Variant.Argon2d, params.variant)
        assertEquals(64 * 1024L, params.memoryKiB)
        assertEquals(100L, params.iterations)
        assertEquals(4, params.parallelism)
    }

    @Test
    fun `engine is not used for aes-kdf`() {
        for (file in listOf(TestDbs.CLI_DEFAULT, TestDbs.KDBX41_AESKDF_KEEPASSXC)) {
            val engine = RecordingEngine()
            KdbxReader(engine).open(TestDbs.file(file), TestDbs.hash()).close()
            assertEquals(0, engine.calls, file)
        }
    }

    @Test
    fun `failing engine gives OTHER, wrong result gives WRONG_KEY`() {
        val throwing = Argon2Engine { _, _ -> throw IllegalStateException("native Fehler") }
        val e1 = assertFailsWith<KdbxOpenException> { KdbxReader(throwing).open(TestDbs.file(TestDbs.ARGON2D_AES), TestDbs.hash()) }
        assertEquals(Reason.OTHER, e1.reason)
        assertFalse(e1.message.orEmpty().contains("native Fehler"))

        val short = Argon2Engine { _, _ -> ByteArray(16) }
        val e2 = assertFailsWith<KdbxOpenException> { KdbxReader(short).open(TestDbs.file(TestDbs.ARGON2D_AES), TestDbs.hash()) }
        assertEquals(Reason.OTHER, e2.reason)

        // A broken native implementation that returns a wrong 32-byte hash
        // cannot be told from a wrong password.
        val wrong = Argon2Engine { _, _ -> ByteArray(32) { 7 } }
        val e3 = assertFailsWith<KdbxOpenException> { KdbxReader(wrong).open(TestDbs.file(TestDbs.ARGON2D_AES), TestDbs.hash()) }
        assertEquals(Reason.WRONG_KEY, e3.reason)
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `kotpass engine matches published test vectors`() {
        // RFC 9106 section 5 (uses secret and associated data, m = 32 KiB).
        fun rfc(variant: Argon2Variant) = KotpassArgon2Engine.hash(
            ByteArray(32) { 1 },
            Argon2Params(variant, 0x13, 32, 3, 4, ByteArray(16) { 2 }, ByteArray(8) { 3 }, ByteArray(12) { 4 }),
        )
        assertContentEquals(hex("512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb"), rfc(Argon2Variant.Argon2d))
        assertContentEquals(hex("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"), rfc(Argon2Variant.Argon2id))

        // Reference implementation test.c: argon2id v0x13, t=2, m=2^16 KiB, p=1,
        // "password" / "somesalt". Usable for native engines without K and X.
        val reference = KotpassArgon2Engine.hash(
            "password".toByteArray(),
            Argon2Params(Argon2Variant.Argon2id, 0x13, 65536, 2, 1, "somesalt".toByteArray(), null, null),
        )
        assertContentEquals(hex("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7"), reference)
    }

    @Test
    fun `kotpass engine passes variant and version through`() {
        val params = Argon2Params(
            variant = Argon2Variant.Argon2id,
            version = 0x13,
            memoryKiB = 64,
            iterations = 3,
            parallelism = 2,
            salt = ByteArray(32) { it.toByte() },
            secret = null,
            associatedData = null,
        )
        val key = ByteArray(32) { (it * 3).toByte() }
        val a = KotpassArgon2Engine.hash(key.copyOf(), params)
        val b = KotpassArgon2Engine.hash(key.copyOf(), params)
        assertEquals(32, a.size)
        assertTrue(a.contentEquals(b))
        val d = KotpassArgon2Engine.hash(key.copyOf(), Argon2Params(Argon2Variant.Argon2d, 0x13, 64, 3, 2, params.salt, null, null))
        val v10 = KotpassArgon2Engine.hash(key.copyOf(), Argon2Params(Argon2Variant.Argon2id, 0x10, 64, 3, 2, params.salt, null, null))
        assertFalse(a.contentEquals(d))
        assertFalse(a.contentEquals(v10))
    }
}
