package de.lulurobb.keypassmobile.kdbx

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * The password component of the KDBX composite key (EN-03).
 */
object PasswordKey {

    /** Length of [hash]'s result in bytes. */
    const val HASH_LENGTH = 32

    /**
     * Returns SHA-256 of the UTF-8 bytes of [password], exactly as KeePassXC
     * derives the password component of the key. This is the value the app
     * stores (encrypted with a biometric-bound Keystore key) and passes to
     * [KdbxReader.open].
     *
     * No String is created. The temporary UTF-8 buffer is overwritten before
     * returning. [password] itself is left unchanged; the caller wipes it.
     * Unpaired surrogates (cannot be typed) are encoded as '?'.
     */
    fun hash(password: CharArray): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        // One UTF-16 unit needs at most 3 UTF-8 bytes (a surrogate pair needs 4
        // for 2 units), so the buffer never overflows and is never reallocated.
        val buffer = ByteBuffer.allocate(password.size * 3)
        try {
            val result = encoder.encode(CharBuffer.wrap(password), buffer, true)
            check(!result.isOverflow) { "UTF-8-Puffer zu klein" }
            check(!encoder.flush(buffer).isOverflow) { "UTF-8-Puffer zu klein" }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(buffer.array(), 0, buffer.position())
            return digest.digest()
        } finally {
            buffer.array().fill(0)
        }
    }
}
