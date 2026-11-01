package de.lulurobb.keypassmobile.kdbx

import de.lulurobb.keypassmobile.kdbx.KdbxOpenException.Reason
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PasswordKeyTest {

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun `hash is sha-256 of the utf-8 bytes`() {
        for (password in listOf(
            "", "a", TestDbs.PASSWORD, TestDbs.PASSWORD_UMLAUT, "€uro", "Smile 😀",
            "x".repeat(1000), "日本語のパスワード",
        )) {
            val hash = PasswordKey.hash(password.toCharArray())
            assertEquals(PasswordKey.HASH_LENGTH, hash.size)
            assertContentEquals(sha256(password.toByteArray(Charsets.UTF_8)), hash, password)
        }
    }

    @Test
    fun `input is left unchanged`() {
        val chars = TestDbs.PASSWORD_UMLAUT.toCharArray()
        PasswordKey.hash(chars)
        assertEquals(TestDbs.PASSWORD_UMLAUT, String(chars))
    }

    @Test
    fun `unpaired surrogate is encoded as question mark`() {
        assertContentEquals(sha256("a?b".toByteArray()), PasswordKey.hash(charArrayOf('a', '\uD800', 'b')))
    }

    @Test
    fun `umlaut password opens like in KeePassXC`() {
        val reader = KdbxReader()
        val vault = reader.open(TestDbs.file(TestDbs.UMLAUT_PASSWORD), PasswordKey.hash(TestDbs.PASSWORD_UMLAUT.toCharArray()))
        assertEquals(TestDbs.BASIC_ENTRY_COUNT, vault.entries.size)
        assertEquals("gh-Test-Passwort-1", String(vault.revealPassword(vault.entries.single { it.title == "GitHub" }.id)))

        // Latin-1 instead of UTF-8 does not work.
        val latin1 = sha256(TestDbs.PASSWORD_UMLAUT.toByteArray(Charsets.ISO_8859_1))
        val e = assertFailsWith<KdbxOpenException> { reader.open(TestDbs.file(TestDbs.UMLAUT_PASSWORD), latin1) }
        assertEquals(Reason.WRONG_KEY, e.reason)
        // Neither does the default password.
        assertEquals(Reason.WRONG_KEY, assertFailsWith<KdbxOpenException> { reader.open(TestDbs.file(TestDbs.UMLAUT_PASSWORD), TestDbs.hash()) }.reason)
    }
}
