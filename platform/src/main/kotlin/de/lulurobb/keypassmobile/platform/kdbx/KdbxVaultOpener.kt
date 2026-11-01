package de.lulurobb.keypassmobile.platform.kdbx

import de.lulurobb.keypassmobile.core.sync.OpenFailure
import de.lulurobb.keypassmobile.core.sync.VaultOpenException
import de.lulurobb.keypassmobile.core.sync.VaultOpener
import de.lulurobb.keypassmobile.kdbx.Argon2Engine
import de.lulurobb.keypassmobile.kdbx.KdbxOpenException
import de.lulurobb.keypassmobile.kdbx.KdbxReader
import de.lulurobb.keypassmobile.model.Vault
import java.io.File

/**
 * [VaultOpener] on :kdbx with a pluggable Argon2 engine (native on Android).
 * WRONG_KEY stays WRONG_KEY (EN-06); unsupported formats, damaged files and
 * other failures count as CORRUPT for the sync engine.
 */
internal class KdbxVaultOpener(argon2: Argon2Engine?) : VaultOpener {

    private val reader = KdbxReader(argon2)

    override fun open(file: File, passwordHash: ByteArray): Vault = try {
        reader.open(file, passwordHash)
    } catch (e: KdbxOpenException) {
        throw VaultOpenException(failureOf(e.reason), e)
    }

    // Fully qualified: the top-level function of :kdbx has the same name as this override.
    override fun looksLikeKdbx(file: File): Boolean = de.lulurobb.keypassmobile.kdbx.looksLikeKdbx(file)

    internal companion object {
        fun failureOf(reason: KdbxOpenException.Reason): OpenFailure = when (reason) {
            KdbxOpenException.Reason.WRONG_KEY -> OpenFailure.WRONG_KEY
            KdbxOpenException.Reason.UNSUPPORTED,
            KdbxOpenException.Reason.CORRUPT,
            KdbxOpenException.Reason.OTHER,
            -> OpenFailure.CORRUPT
        }
    }
}
