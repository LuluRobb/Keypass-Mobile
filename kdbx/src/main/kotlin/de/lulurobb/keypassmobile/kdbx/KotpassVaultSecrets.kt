package de.lulurobb.keypassmobile.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.EntryValue
import de.lulurobb.keypassmobile.model.VaultSecrets
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * Entry passwords in kotpass' protected form (SE-04). For protected values
 * that is the ciphertext of the KDBX inner stream plus its key stream, so
 * the plain password is never held in memory until [password] is called.
 *
 * Only the passwords of the entries in the vault are kept, nothing else of
 * the kotpass database (no credentials, no other fields, no attachments).
 * Thread-safe; [close] may run concurrently with [password].
 */
internal class KotpassVaultSecrets(passwords: Map<UUID, ProtectedPassword>) : VaultSecrets {

    @Volatile
    private var passwords: Map<UUID, ProtectedPassword>? = passwords

    /**
     * Returns a fresh array with the password of [entryId]; empty if the entry
     * has none. Throws [IllegalStateException] after [close] and
     * [NoSuchElementException] for an id that is not in the vault.
     */
    override fun password(entryId: UUID): CharArray {
        val current = passwords ?: throw IllegalStateException("Die Datenbank ist gesperrt.")
        val value = current[entryId] ?: throw NoSuchElementException("Unbekannter Eintrag.")
        return value.reveal()
    }

    /** Drops all password references. Idempotent. */
    override fun close() {
        passwords = null
    }

    override fun toString(): String = "KotpassVaultSecrets(${if (passwords == null) "geschlossen" else "offen"})"
}

/**
 * One password in protected form. Normally the kotpass [EncryptedValue] of
 * the entry. Falls back to the kotpass [EntryValue] if its EncryptedValue
 * cannot be read (then every reveal creates a String, see [KotpassInternals]).
 */
internal class ProtectedPassword private constructor(
    private val encrypted: EncryptedValue?,
    private val fallback: EntryValue?,
) {
    fun reveal(): CharArray {
        if (encrypted != null) {
            val bytes = encrypted.getBinary()
            try {
                return utf8ToChars(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        return fallback?.content?.toCharArray() ?: CharArray(0)
    }

    override fun toString(): String = "ProtectedPassword"

    companion object {
        private val EMPTY = ProtectedPassword(null, null)

        fun of(value: EntryValue?): ProtectedPassword = when (value) {
            null -> EMPTY
            is EntryValue.Encrypted -> KotpassInternals.encryptedValue(value)
                ?.let { ProtectedPassword(it, null) }
                ?: ProtectedPassword(null, value)
            // Unprotected in the file (memory protection switched off in
            // KeePassXC). The String stays in the dropped kotpass model until
            // garbage collection; from now on only the masked bytes are kept.
            is EntryValue.Plain -> if (value.isEmpty()) {
                EMPTY
            } else {
                // fromBinary masks the array in place and keeps it.
                ProtectedPassword(EncryptedValue.fromBinary(value.content.toByteArray(Charsets.UTF_8)), null)
            }
        }
    }
}

/**
 * Decodes UTF-8 [bytes] into a new CharArray without creating a String. The
 * intermediate buffer is overwritten. Invalid sequences become U+FFFD.
 */
internal fun utf8ToChars(bytes: ByteArray): CharArray {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    // UTF-8 never yields more UTF-16 units than bytes, so this buffer is
    // never too small and never reallocated.
    val out = CharBuffer.allocate(bytes.size)
    try {
        val result = decoder.decode(ByteBuffer.wrap(bytes), out, true)
        check(!result.isOverflow) { "UTF-8-Puffer zu klein" }
        check(!decoder.flush(out).isOverflow) { "UTF-8-Puffer zu klein" }
        out.flip()
        return CharArray(out.remaining()).also { out.get(it) }
    } finally {
        out.array().fill('\u0000')
    }
}
