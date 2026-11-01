package de.lulurobb.keypassmobile.platform

import android.security.keystore.KeyExpiredException
import android.security.keystore.KeyInfo
import android.security.keystore.KeyNotYetValidException
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.platform.keystore.DecryptPreparation
import de.lulurobb.keypassmobile.platform.keystore.KeySpecs
import de.lulurobb.keypassmobile.platform.keystore.KeystoreHashStore
import de.lulurobb.keypassmobile.platform.keystore.SmbPasswordStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.PrivateKey
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Keystore parts that run without a fingerprint: AES for the SMB password,
 * RSA-OAEP for the password hash with a key generated from the same
 * specification but without user authentication.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTest {

    private val dir = File(context.noBackupFilesDir, "test-keys").apply { mkdirs() }
    private val aliases = listOf("test_smb", "test_smb_sb", "test_hash", "test_hash_sb")

    @After
    fun cleanUp() {
        aliases.forEach(KeySpecs::deleteKeyQuietly)
        dir.deleteRecursively()
    }

    private fun hashStore(alias: String = "test_hash", strongBox: Boolean = false) =
        KeystoreHashStore(File(dir, "$alias.bin"), strongBoxAvailable = strongBox, authRequired = false, alias = alias)

    private fun ByteArray.contains(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    // ---- SMB password (AES-256-GCM, DS-03) ----

    @Test
    fun smbPasswordRoundTrip() {
        val file = File(dir, "smb.bin")
        val store = SmbPasswordStore(file, strongBoxAvailable = false, alias = "test_smb")
        assertNull(store.load())

        store.save("Prüfung-Äß-2026!".toCharArray())
        assertArrayEquals("Prüfung-Äß-2026!".toCharArray(), store.load())
        assertFalse("no plaintext on disk", file.readBytes().contains("Prüfung".toByteArray()))

        // The key is reused, every save gets a fresh IV.
        val first = file.readBytes()
        store.save("Prüfung-Äß-2026!".toCharArray())
        assertFalse(first.contentEquals(file.readBytes()))
        store.save("neu".toCharArray())
        assertArrayEquals("neu".toCharArray(), store.load())

        store.clear()
        assertNull(store.load())
        assertFalse(KeySpecs.keyStore().containsAlias("test_smb"))
    }

    @Test
    fun smbPasswordFallsBackWithoutStrongBox() {
        // On the emulator StrongBox is missing: the store falls back to the TEE.
        val store = SmbPasswordStore(File(dir, "smb-sb.bin"), strongBoxAvailable = true, alias = "test_smb_sb")
        store.save("geheim".toCharArray())
        assertArrayEquals("geheim".toCharArray(), store.load())
    }

    // ---- password hash (RSA-OAEP, EN-03 to EN-05) ----

    @Test
    fun hashRoundTripWithExportedPublicKey() {
        val store = hashStore()
        val hash = ByteArray(32) { (it * 3 + 1).toByte() }
        store.store(hash, Instant.now().plus(Duration.ofDays(7)))
        assertTrue(store.hasStoredValue())

        val ready = store.prepareDecrypt() as DecryptPreparation.Ready
        assertEquals(256, ready.ciphertext.size)
        assertArrayEquals(hash, ready.cipher.doFinal(ready.ciphertext))
    }

    @Test
    fun publicKeyIsExportedToSoftware() {
        val store = hashStore()
        store.store(ByteArray(32), Instant.now().plus(Duration.ofDays(1)))
        val keystorePublic = KeySpecs.keyStore().getCertificate("test_hash").publicKey
        val exported = KeystoreHashStore.exportPublic(keystorePublic)
        assertEquals("X.509", exported.format)
        assertFalse(exported.javaClass.name.contains("AndroidKeyStore"))
        // A second encryption with the exported key decrypts with the keystore key.
        val ciphertext = KeystoreHashStore.encryptWithPublicKey(exported, ByteArray(32) { 7 })
        val ready = store.prepareDecrypt() as DecryptPreparation.Ready
        assertArrayEquals(ByteArray(32) { 7 }, ready.cipher.doFinal(ciphertext))
    }

    @Test
    fun keyValidityEndIsSet() {
        val end = Instant.now().plus(Duration.ofDays(3)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        hashStore().store(ByteArray(32), end)
        val key = KeySpecs.keyStore().getKey("test_hash", null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, KeySpecs.ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        assertEquals(Date.from(end), info.keyValidityForConsumptionEnd)
        assertEquals(2048, info.keySize)
        assertFalse("test key without authentication", info.isUserAuthenticationRequired)
    }

    @Test
    fun authenticatedSpecRequiresStrongBiometricPerUse() {
        val end = Date.from(Instant.now().plus(Duration.ofDays(7)))
        val spec = KeySpecs.hashKeySpec("unused", end, authRequired = true, strongBox = true)
        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
        assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
        assertTrue(spec.isInvalidatedByBiometricEnrollment)
        assertTrue(spec.isStrongBoxBacked)
        assertEquals(end, spec.keyValidityForConsumptionEnd)
        assertArrayEquals(arrayOf(KeyProperties.DIGEST_SHA256), spec.digests)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP), spec.encryptionPaddings)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
    }

    @Test
    fun expiredKeyIsReportedAndDeleted() {
        val store = hashStore()
        store.store(ByteArray(32), Instant.now().minus(Duration.ofMinutes(1)))
        assertEquals(DecryptPreparation.Expired, store.prepareDecrypt())
        assertFalse(store.hasStoredValue())
        assertFalse(KeySpecs.keyStore().containsAlias("test_hash"))
    }

    @Test
    fun missingKeyDeletesOrphanedCiphertext() {
        val store = hashStore()
        store.store(ByteArray(32), Instant.now().plus(Duration.ofDays(1)))
        KeySpecs.deleteKeyQuietly("test_hash")
        assertEquals(DecryptPreparation.Missing, store.prepareDecrypt())
        assertFalse(store.hasStoredValue())
    }

    @Test
    fun newEntryReplacesKeyAndValue() {
        val store = hashStore()
        store.store(ByteArray(32) { 1 }, Instant.now().plus(Duration.ofDays(1)))
        store.store(ByteArray(32) { 2 }, Instant.now().plus(Duration.ofDays(2)))
        val ready = store.prepareDecrypt() as DecryptPreparation.Ready
        assertArrayEquals(ByteArray(32) { 2 }, ready.cipher.doFinal(ready.ciphertext))
        store.clear()
        assertEquals(DecryptPreparation.Missing, store.prepareDecrypt())
    }

    @Test
    fun hashStoreFallsBackWithoutStrongBox() {
        val store = hashStore(alias = "test_hash_sb", strongBox = true)
        store.store(ByteArray(32) { 9 }, Instant.now().plus(Duration.ofDays(1)))
        val ready = store.prepareDecrypt() as DecryptPreparation.Ready
        assertArrayEquals(ByteArray(32) { 9 }, ready.cipher.doFinal(ready.ciphertext))
    }

    @Test
    fun keystoreExceptionsAreClassified() {
        assertEquals(DecryptPreparation.Invalidated, KeystoreHashStore.classify(KeyPermanentlyInvalidatedException()))
        assertEquals(
            DecryptPreparation.Invalidated,
            KeystoreHashStore.classify(InvalidKeyException("wrapped", KeyPermanentlyInvalidatedException())),
        )
        assertEquals(DecryptPreparation.Expired, KeystoreHashStore.classify(KeyExpiredException()))
        assertEquals(DecryptPreparation.Failed, KeystoreHashStore.classify(KeyNotYetValidException()))
        assertEquals(DecryptPreparation.Failed, KeystoreHashStore.classify(IllegalStateException()))
    }
}
