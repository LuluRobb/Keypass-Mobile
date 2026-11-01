package de.lulurobb.keypassmobile.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.core.sync.OpenFailure
import de.lulurobb.keypassmobile.core.sync.VaultOpenException
import de.lulurobb.keypassmobile.kdbx.Argon2Params
import de.lulurobb.keypassmobile.kdbx.Argon2Variant
import de.lulurobb.keypassmobile.kdbx.KotpassArgon2Engine
import de.lulurobb.keypassmobile.kdbx.PasswordKey
import de.lulurobb.keypassmobile.platform.kdbx.KdbxVaultOpener
import de.lulurobb.keypassmobile.platform.kdbx.NativeArgon2Engine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native Argon2 (argon2kt, JNI) and opening real KDBX files on the device. */
@RunWith(AndroidJUnit4::class)
class NativeKdbxTest {

    private fun params(variant: Argon2Variant, version: Int, memoryKiB: Long, iterations: Long, parallelism: Int, salt: ByteArray) =
        Argon2Params(variant, version, memoryKiB, iterations, parallelism, salt, secret = null, associatedData = null)

    @Test
    fun argon2idKnownAnswer() {
        // Reference vector: argon2id, v=0x13, t=2, m=65536, p=1, "password" / "somesalt".
        val hash = NativeArgon2Engine().hash(
            "password".toByteArray(),
            params(Argon2Variant.Argon2id, 0x13, 65_536, 2, 1, "somesalt".toByteArray()),
        )
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7", hash.hex())
    }

    @Test
    fun nativeMatchesKotpassForBothVariantsAndVersions() {
        val salt = ByteArray(32) { (it * 7).toByte() }
        val password = ByteArray(32) { (255 - it).toByte() }
        for (variant in Argon2Variant.entries) {
            for (version in listOf(0x10, 0x13)) {
                val p = params(variant, version, 1_024, 3, 2, salt)
                assertArrayEquals(
                    "$variant v$version",
                    KotpassArgon2Engine.hash(password.copyOf(), p),
                    NativeArgon2Engine().hash(password.copyOf(), p),
                )
            }
        }
    }

    @Test
    fun opensRealDatabasesWithNativeArgon2() {
        val opener = KdbxVaultOpener(NativeArgon2Engine())
        for (name in listOf("argon2id-aes.kdbx", "gui-default-argon2d.kdbx")) {
            val file = asset(name)
            assertTrue(opener.looksLikeKdbx(file))
            val hash = PasswordKey.hash(TEST_DB_PASSWORD.toCharArray())
            val vault = opener.open(file, hash)
            assertEquals(name, 3, vault.entries.size)
            assertFalse("hash is not modified", hash.all { it == 0.toByte() })
            vault.close()
        }
    }

    @Test
    fun wrongPasswordIsWrongKey() {
        val opener = KdbxVaultOpener(NativeArgon2Engine())
        val e = assertThrows(VaultOpenException::class.java) {
            opener.open(asset("argon2id-aes.kdbx"), PasswordKey.hash("falsch".toCharArray()))
        }
        assertEquals(OpenFailure.WRONG_KEY, e.failure)
    }

    @Test
    fun noKdbxIsCorrupt() {
        val file = java.io.File(context.cacheDir, "not-a-db.kdbx").apply { writeText("<html>login</html>") }
        val opener = KdbxVaultOpener(NativeArgon2Engine())
        assertFalse(opener.looksLikeKdbx(file))
        val e = assertThrows(VaultOpenException::class.java) {
            opener.open(file, PasswordKey.hash(TEST_DB_PASSWORD.toCharArray()))
        }
        assertEquals(OpenFailure.CORRUPT, e.failure)
    }
}
