package de.lulurobb.keypassmobile.platform.keystore

import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The SMB password, encrypted with an AES-256-GCM key in the AndroidKeyStore
 * that needs no user authentication (DS-03), so the background sync can use
 * it. Ciphertext in [file] (noBackupFilesDir, SE-08). The password is never
 * turned into a String here.
 */
internal class SmbPasswordStore(
    private val file: File,
    private val strongBoxAvailable: Boolean,
    private val alias: String = KeySpecs.SMB_KEY_ALIAS,
) {
    private val lock = Any()

    fun hasStoredValue(): Boolean = file.isFile

    /** Encrypts and stores [password]. [password] is not modified; the caller wipes it. */
    fun save(password: CharArray) = synchronized(lock) {
        val plain = utf8(password)
        try {
            val (iv, ciphertext) = encrypt(plain)
            val out = ByteArray(2 + iv.size + ciphertext.size)
            out[0] = FORMAT_V1
            out[1] = iv.size.toByte()
            iv.copyInto(out, 2)
            ciphertext.copyInto(out, 2 + iv.size)
            writeAtomically(file, out)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Decrypts the stored password. The caller wipes the returned array after
     * use. Null when nothing is stored or the key is gone.
     */
    fun load(): CharArray? = synchronized(lock) {
        val bytes = try {
            if (!file.isFile) return@synchronized null
            file.readBytes()
        } catch (_: Exception) {
            return@synchronized null
        }
        if (bytes.size < 2 || bytes[0] != FORMAT_V1) return@synchronized null
        val ivLength = bytes[1].toInt() and 0xFF
        if (bytes.size < 2 + ivLength + GCM_TAG_BYTES) return@synchronized null
        val key = existingKey() ?: return@synchronized null
        val plain = try {
            val cipher = Cipher.getInstance(KeySpecs.AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(KeySpecs.GCM_TAG_BITS, bytes, 2, ivLength))
            cipher.doFinal(bytes, 2 + ivLength, bytes.size - 2 - ivLength)
        } catch (_: Exception) {
            return@synchronized null
        }
        try {
            fromUtf8(plain)
        } finally {
            plain.fill(0)
        }
    }

    /** Deletes the stored password and its key. Never throws. */
    fun clear() = synchronized(lock) {
        file.delete()
        KeySpecs.deleteKeyQuietly(alias)
    }

    private fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        existingKey()?.let { key ->
            try {
                return encryptWith(key, plain)
            } catch (_: Exception) {
                // Broken key: replace it below.
                KeySpecs.deleteKeyQuietly(alias)
            }
        }
        if (strongBoxAvailable) {
            try {
                return encryptWith(generate(strongBox = true), plain)
            } catch (_: StrongBoxUnavailableException) {
                // Fall back to the TEE.
            } catch (_: Exception) {
                KeySpecs.deleteKeyQuietly(alias)
            }
        }
        return encryptWith(generate(strongBox = false), plain)
    }

    private fun encryptWith(key: SecretKey, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(KeySpecs.AES_TRANSFORMATION)
        // The keystore creates a random IV (randomized encryption is required).
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plain)
        return cipher.iv to ciphertext
    }

    private fun existingKey(): SecretKey? = try {
        KeySpecs.keyStore().getKey(alias, null) as? SecretKey
    } catch (_: Exception) {
        null
    }

    private fun generate(strongBox: Boolean): SecretKey =
        KeyGenerator.getInstance("AES", KeySpecs.ANDROID_KEYSTORE).run {
            init(KeySpecs.smbKeySpec(alias, strongBox))
            generateKey()
        }

    internal companion object {
        const val FORMAT_V1: Byte = 1
        const val GCM_TAG_BYTES = KeySpecs.GCM_TAG_BITS / 8

        /** UTF-8 bytes of [chars] without creating a String; the temporary buffer is wiped. */
        fun utf8(chars: CharArray): ByteArray {
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val buffer = ByteBuffer.allocate(chars.size * 3)
            try {
                encoder.encode(CharBuffer.wrap(chars), buffer, true)
                encoder.flush(buffer)
                return buffer.array().copyOf(buffer.position())
            } finally {
                buffer.array().fill(0)
            }
        }

        /** Chars of UTF-8 [bytes] without creating a String; the temporary buffer is wiped. */
        fun fromUtf8(bytes: ByteArray): CharArray {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val buffer = CharBuffer.allocate(bytes.size)
            try {
                decoder.decode(ByteBuffer.wrap(bytes), buffer, true)
                decoder.flush(buffer)
                return buffer.array().copyOf(buffer.position())
            } finally {
                buffer.array().fill('\u0000')
            }
        }
    }
}
