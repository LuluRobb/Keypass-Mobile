package de.lulurobb.keypassmobile.platform.keystore

import android.security.keystore.KeyExpiredException
import android.security.keystore.KeyNotYetValidException
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Date
import javax.crypto.Cipher

/**
 * Stores the password hash for the fingerprint unlock (EN-03 to EN-05).
 * Only the 32 hash bytes are stored, never the password. Implemented with
 * the keystore by [KeystoreHashStore]; the session only needs this part.
 */
interface HashStore {
    /** True if an encrypted hash exists (it may still turn out expired or invalidated). */
    fun hasStoredValue(): Boolean

    /**
     * Replaces the stored value: deletes the old one, creates a new key pair
     * that cannot decrypt after [validityEnd], encrypts [hash] with its public
     * key. Blocking; key generation can take seconds. [hash] is not modified.
     *
     * @throws Exception when the key cannot be created, e.g. no fingerprint enrolled.
     */
    fun store(hash: ByteArray, validityEnd: Instant)

    /** Deletes the stored value and its key. Never throws. */
    fun clear()
}

/** Result of [KeystoreHashStore.prepareDecrypt]. */
internal sealed interface DecryptPreparation {
    /** Ready for the BiometricPrompt: [cipher] goes into the CryptoObject, then decrypts [ciphertext]. */
    class Ready(val cipher: Cipher, val ciphertext: ByteArray) : DecryptPreparation

    /** Nothing stored (or the key is gone; the orphaned ciphertext was deleted). */
    data object Missing : DecryptPreparation

    /** A fingerprint was enrolled since the key was created (EN-04). The stored value was deleted. */
    data object Invalidated : DecryptPreparation

    /** The key's validity ended (EN-05). The stored value was deleted. */
    data object Expired : DecryptPreparation

    /** Any other keystore error; the stored value is kept (may be transient). */
    data object Failed : DecryptPreparation
}

/**
 * [HashStore] with an RSA-2048 key pair in the AndroidKeyStore (section 9):
 * encrypting with the exported public key needs no fingerprint, decrypting
 * with the private key only works inside a BiometricPrompt with CryptoObject.
 * A new pair is created at every master password entry, valid until the
 * entry plus the password interval. Ciphertext in [file] (noBackupFilesDir).
 *
 * @param authRequired false only in tests: same key specification without
 *   user authentication (the biometric path cannot be automated).
 */
internal class KeystoreHashStore(
    private val file: File,
    private val strongBoxAvailable: Boolean,
    private val authRequired: Boolean = true,
    private val alias: String = KeySpecs.HASH_KEY_ALIAS,
) : HashStore {

    /** Serializes store, clear and prepare, so a fingerprint unlock never sees half a replacement. */
    private val lock = Any()

    override fun hasStoredValue(): Boolean = file.isFile

    override fun store(hash: ByteArray, validityEnd: Instant) = synchronized(lock) {
        require(hash.size == HASH_LENGTH) { "hash must be 32 bytes" }
        deleteAll()
        val publicKey = generateKeyPair(Date.from(validityEnd))
        val ciphertext = encryptWithPublicKey(publicKey, hash)
        try {
            writeAtomically(file, byteArrayOf(FORMAT_V1) + ciphertext)
        } catch (e: Exception) {
            deleteAll()
            throw e
        }
    }

    override fun clear() = synchronized(lock) { deleteAll() }

    /**
     * Initializes the decrypting cipher for the BiometricPrompt. Maps an
     * invalidated key (EN-04) and an expired key (EN-05) to results and deletes
     * the stored value in both cases. Blocking (keystore call), but fast.
     */
    fun prepareDecrypt(): DecryptPreparation = synchronized(lock) {
        val stored = readCiphertext() ?: return@synchronized DecryptPreparation.Missing
        val key = try {
            KeySpecs.keyStore().getKey(alias, null) as? PrivateKey
        } catch (e: Exception) {
            return@synchronized classify(e).also { if (it.deletesValue) deleteAll() }
        }
        if (key == null) {
            deleteAll()
            return@synchronized DecryptPreparation.Missing
        }
        try {
            val cipher = Cipher.getInstance(KeySpecs.RSA_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, KeySpecs.oaep())
            DecryptPreparation.Ready(cipher, stored)
        } catch (e: Exception) {
            classify(e).also { if (it.deletesValue) deleteAll() }
        }
    }

    /** Ciphertext without the format byte, or null when nothing (valid) is stored. */
    private fun readCiphertext(): ByteArray? {
        if (!file.isFile) return null
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        if (bytes.size != 1 + RSA_BLOCK || bytes[0] != FORMAT_V1) {
            deleteAll()
            return null
        }
        return bytes.copyOfRange(1, bytes.size)
    }

    private fun generateKeyPair(validityEnd: Date): PublicKey {
        if (strongBoxAvailable) {
            try {
                val pair = generate(validityEnd, strongBox = true)
                // StrongBox supports fewer parameters than the TEE; check that the
                // private key accepts the OAEP parameters before relying on it.
                // init needs no authentication, only the later doFinal does.
                Cipher.getInstance(KeySpecs.RSA_TRANSFORMATION).init(Cipher.DECRYPT_MODE, pair.private, KeySpecs.oaep())
                return exportPublic(pair.public)
            } catch (_: StrongBoxUnavailableException) {
                // Fall back to the TEE below.
            } catch (_: Exception) {
                KeySpecs.deleteKeyQuietly(alias)
            }
        }
        return exportPublic(generate(validityEnd, strongBox = false).public)
    }

    private fun generate(validityEnd: Date, strongBox: Boolean) =
        KeyPairGenerator.getInstance("RSA", KeySpecs.ANDROID_KEYSTORE).run {
            initialize(KeySpecs.hashKeySpec(alias, validityEnd, authRequired, strongBox))
            generateKeyPair()
        }

    private fun deleteAll() {
        file.delete()
        KeySpecs.deleteKeyQuietly(alias)
    }

    internal companion object {
        const val HASH_LENGTH = 32
        const val RSA_BLOCK = KeySpecs.RSA_KEY_SIZE / 8
        const val FORMAT_V1: Byte = 1

        /**
         * Software copy of a keystore public key, so encrypting runs in the
         * normal JCA provider without any keystore restriction.
         */
        fun exportPublic(key: PublicKey): PublicKey =
            KeyFactory.getInstance(key.algorithm).generatePublic(X509EncodedKeySpec(key.encoded))

        /** RSA-OAEP with the explicit parameters of [KeySpecs.oaep]. */
        fun encryptWithPublicKey(publicKey: PublicKey, plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(KeySpecs.RSA_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, KeySpecs.oaep())
            return cipher.doFinal(plain)
        }

        /** Maps keystore exceptions of key loading and cipher init. */
        fun classify(e: Throwable): DecryptPreparation {
            var current: Throwable? = e
            var depth = 0
            while (current != null && depth < 8) {
                when (current) {
                    is KeyPermanentlyInvalidatedException -> return DecryptPreparation.Invalidated
                    is KeyExpiredException -> return DecryptPreparation.Expired
                    is KeyNotYetValidException -> return DecryptPreparation.Failed
                }
                current = current.cause
                depth++
            }
            return DecryptPreparation.Failed
        }

        private val DecryptPreparation.deletesValue: Boolean
            get() = this == DecryptPreparation.Invalidated || this == DecryptPreparation.Expired
    }
}
