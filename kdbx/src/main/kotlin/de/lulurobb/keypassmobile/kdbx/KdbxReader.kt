package de.lulurobb.keypassmobile.kdbx

import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import de.lulurobb.keypassmobile.kdbx.KdbxOpenException.Reason
import de.lulurobb.keypassmobile.model.Vault
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.BufferUnderflowException

/**
 * Opens KDBX files read-only with kotpass and maps them to [Vault].
 *
 * Supported (kotpass 0.13.0): KDBX 3.1, 4.0 and 4.1; Argon2d, Argon2id and
 * AES-KDF; AES-256, ChaCha20 and Twofish. Only databases protected by a
 * password alone (no key file, no YubiKey, KX-04).
 *
 * @param argon2 Argon2 implementation for the key derivation, e.g. native
 *   argon2kt on Android. Null uses kotpass' pure-Kotlin Argon2
 *   ([KotpassArgon2Engine]). AES-KDF always uses kotpass.
 */
class KdbxReader(private val argon2: Argon2Engine? = null) {

    /**
     * Decrypts [file] with [passwordHash] (SHA-256 of the master password,
     * see [PasswordKey.hash]) and returns the unlocked vault.
     *
     * [passwordHash] is neither kept nor modified; the caller owns it (it
     * stays in memory while unlocked for SY-04, SE-04). Internally the hash
     * is passed to kotpass as 32-byte key file component, which yields the
     * same composite key as the password, and kotpass' copy is overwritten
     * after decoding.
     *
     * The returned [Vault] keeps no reference to the kotpass database: only
     * the mapped entries, the used custom icons and the entry passwords in
     * kotpass' protected form. Call [Vault.close] on lock.
     *
     * Runs the key derivation on the calling thread (about 1 s on the target
     * phone with native Argon2), so call it off the main thread. Reads the
     * whole file into memory (it is encrypted; KeePassXC files are small).
     *
     * Error mapping: a truncated or damaged KDBX 4 file is CORRUPT. KDBX 3.1
     * has no header MAC, so a 3.1 file cut at a cipher block boundary is
     * reported as WRONG_KEY. A native [Argon2Engine] that returns a wrong
     * 32-byte hash also shows up as WRONG_KEY.
     *
     * @throws KdbxOpenException with [Reason] WRONG_KEY, UNSUPPORTED, CORRUPT
     *   or OTHER. Messages are German and never contain secrets.
     * @throws IllegalArgumentException if [passwordHash] is not 32 bytes long.
     */
    fun open(file: File, passwordHash: ByteArray): Vault {
        require(passwordHash.size == PasswordKey.HASH_LENGTH) { "Passwort-Hash muss 32 Byte lang sein." }
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            throw KdbxOpenException(Reason.OTHER, "Die Datei konnte nicht gelesen werden.", SanitizedException(e))
        }
        val database = decode(bytes, passwordHash)
        val content = try {
            VaultMapper.map(database.content)
        } catch (e: Exception) {
            throw KdbxOpenException(Reason.OTHER, "Die Datenbank konnte nicht gelesen werden.", SanitizedException(e))
        }
        return Vault(
            rootGroupName = content.rootGroupName,
            entries = content.entries,
            customIcons = content.customIcons,
            secrets = KotpassVaultSecrets(content.passwords),
        )
    }

    /** Decodes [bytes] with kotpass. Internal for timing tests. */
    internal fun decode(bytes: ByteArray, passwordHash: ByteArray): KeePassDatabase {
        // kotpass masks this array in place and keeps it in Credentials (a
        // 32-byte key file is used as-is). Overwriting it afterwards destroys
        // that copy; the caller's array is never touched.
        val keyCopy = passwordHash.copyOf()
        try {
            return KeePassDatabase.decode(
                inputStream = ByteArrayInputStream(bytes),
                credentials = Credentials.from(keyCopy),
                cipherProviders = CIPHERS,
                kdfProvider = EngineKdfProvider(argon2),
            )
        } catch (e: Exception) {
            throw translate(e, bytes)
        } catch (e: OutOfMemoryError) {
            // A single large allocation (Argon2 memory or file content) failed
            // and is unreachable now; report it instead of crashing.
            throw KdbxOpenException(Reason.OTHER, "Nicht genug Arbeitsspeicher zum Entschlüsseln.", SanitizedException(e))
        } finally {
            keyCopy.fill(0)
        }
    }

    private fun translate(e: Exception, bytes: ByteArray): KdbxOpenException {
        val cause = SanitizedException(e)
        if (e is KdfFailure) {
            return KdbxOpenException(Reason.OTHER, "Die Schlüsselableitung ist fehlgeschlagen.", cause)
        }
        val inspection = KdbxHeader.inspect(bytes)
        if (e is CryptoError.InvalidKey) {
            return if (KdbxHeader.isTruncatedKdbx3(inspection, bytes.size)) {
                KdbxOpenException(Reason.CORRUPT, CORRUPT_MESSAGE, cause)
            } else {
                KdbxOpenException(Reason.WRONG_KEY, "Falsches Master-Passwort.", cause)
            }
        }
        if (inspection is KdbxHeader.Inspection.NotKeePass) {
            return KdbxOpenException(Reason.CORRUPT, "Die Datei ist keine KDBX-Datenbank.", cause)
        }
        val unsupported = KdbxHeader.unsupportedDetail(inspection)
        if (unsupported != null || e is FormatError.UnsupportedVersion || e is CryptoError.AlgorithmUnavailable) {
            val detail = unsupported?.let { ": $it" } ?: "."
            return KdbxOpenException(Reason.UNSUPPORTED, "Dieses Datenbankformat wird nicht unterstützt$detail", cause)
        }
        return when (e) {
            is FormatError,
            is CryptoError,
            is IOException,
            is BufferUnderflowException,
            is IndexOutOfBoundsException,
            is IllegalArgumentException,
            is NoSuchElementException,
            -> KdbxOpenException(Reason.CORRUPT, CORRUPT_MESSAGE, cause)
            else -> KdbxOpenException(Reason.OTHER, "Die Datenbank konnte nicht geöffnet werden.", cause)
        }
    }

    private companion object {
        val CIPHERS = BaseCiphers.entries + TwofishCipher
        const val CORRUPT_MESSAGE = "Die Datenbank ist beschädigt oder unvollständig."
    }
}
