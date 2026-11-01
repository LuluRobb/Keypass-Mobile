package de.lulurobb.keypassmobile.core.sync

import de.lulurobb.keypassmobile.model.Vault
import java.io.File

/** Opens KDBX files (implemented by :app on top of :kdbx). */
interface VaultOpener {
    /**
     * Decrypts [file] with [passwordHash], the SHA-256 of the master password
     * (EN-03). Must not keep a reference to [passwordHash]; the caller owns and
     * wipes it.
     *
     * @throws VaultOpenException with [OpenFailure.WRONG_KEY] when the key does
     *   not fit, [OpenFailure.CORRUPT] when the file is damaged or no KDBX.
     *   Any other exception is treated like [OpenFailure.CORRUPT].
     */
    fun open(file: File, passwordHash: ByteArray): Vault

    /** Cheap check of the file signature without a key (SY-06). Must not throw. */
    fun looksLikeKdbx(file: File): Boolean
}

/** Why [VaultOpener.open] failed. */
enum class OpenFailure {
    /** The key does not open the file (e.g. master password changed on the PC). */
    WRONG_KEY,

    /** The file is damaged, truncated or not a KDBX file. */
    CORRUPT,
}

/** Failure of [VaultOpener.open]. Carries no key material (SE-07). */
class VaultOpenException(val failure: OpenFailure, cause: Throwable? = null) : Exception(failure.name, cause)
