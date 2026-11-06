package de.lulurobb.keypassmobile.platform.settings

import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.util.UUID

/**
 * The SMB source of the database (DS-01, DS-02). Holds no secret: the SMB
 * password is stored encrypted in the keystore (DS-03).
 */
data class SourceConfig(
    val host: String,
    val port: Int,
    /** NTLM domain or workgroup, usually empty. */
    val domain: String,
    val user: String,
    val share: String,
    /** Path below the share: folders, then the file name, e.g. ["KeePass", "Passwords.kdbx"]. */
    val filePath: List<String>,
) {
    init {
        require(filePath.isNotEmpty()) { "filePath must name a file" }
    }

    val fileName: String get() = filePath.last()

    /** Folders of [filePath] without the file name, where a source change starts browsing (DS-02). */
    val folderPath: List<String> get() = filePath.dropLast(1)

    /** UNC form for display: \\host\share\folder\file.kdbx */
    val unc: String get() = "\\\\" + (listOf(host, share) + filePath).joinToString("\\")

    /** True if both point to the same file on the same server (SMB names are case-insensitive). */
    fun sameFileAs(other: SourceConfig?): Boolean =
        other != null &&
            host.equals(other.host, ignoreCase = true) &&
            port == other.port &&
            share.equals(other.share, ignoreCase = true) &&
            filePath.size == other.filePath.size &&
            filePath.zip(other.filePath).all { (a, b) -> a.equals(b, ignoreCase = true) }

    /** True if both address the same server (host and port). */
    fun sameServerAs(host: String, port: Int): Boolean = this.host.equals(host, ignoreCase = true) && this.port == port
}

/** The server identity remembered at setup or the first successful sync (DS-04). */
data class TrustedServer(
    val guid: UUID,
    /** When the GUID was remembered or last accepted. */
    val since: Instant,
)

/**
 * All persisted settings. Nothing secret (SE-07): no passwords, no hashes.
 * Excluded from backup like all app data (SE-08).
 */
data class KpmSettings(
    val source: SourceConfig? = null,
    val trustedServer: TrustedServer? = null,
    /**
     * GUID a sync saw instead of [trustedServer] (DS-04). The UI asks once
     * whether to trust it; cleared when accepted or when the remembered
     * server answers again.
     */
    val pendingServerGuid: UUID? = null,
    val fingerprintEnabled: Boolean = false,
    val passwordInterval: PasswordInterval = PasswordInterval.DEFAULT,
    val idleTimeout: IdleTimeout = IdleTimeout.DEFAULT,
    /** The first-run setup was finished (source chosen, unlocked once, system hints shown). */
    val setupCompleted: Boolean = false,
    /** Last time the master password was typed and opened the database (EN-05). */
    val lastPasswordEntry: Instant? = null,
    /** [passwordInterval] at [lastPasswordEntry]; the keystore key expires after it (EN-05). */
    val intervalAtEntry: PasswordInterval? = null,
    /**
     * The stored fingerprint value was deleted because the master password was
     * due (EN-05). The unlock screen keeps saying why until the next typed
     * password.
     */
    val fingerprintExpired: Boolean = false,
)

/** Persisted settings as a flow. Updates are atomic and visible at once. */
interface SettingsRepository {
    val settings: StateFlow<KpmSettings>

    /** Applies [transform] atomically and persists the result. Returns the new settings. */
    fun update(transform: (KpmSettings) -> KpmSettings): KpmSettings
}
