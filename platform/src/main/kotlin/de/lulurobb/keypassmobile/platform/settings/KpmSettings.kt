package de.lulurobb.keypassmobile.platform.settings

import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.util.UUID

/** Where the database file comes from (DS-01, DS-08). */
enum class SourceKind { SMB, FILE }

/**
 * The source of the database: exactly one at a time, an SMB share or a file on
 * the phone (DS-08). Holds no secret: the SMB password is stored encrypted in
 * the keystore (DS-03).
 */
sealed interface SourceConfig {
    /** Name of the database file, as shown to the user. */
    val fileName: String

    val kind: SourceKind
}

/** The SMB source of the database (DS-01, DS-02). */
data class SmbSource(
    val host: String,
    val port: Int,
    /** NTLM domain or workgroup, usually empty. */
    val domain: String,
    val user: String,
    val share: String,
    /** Path below the share: folders, then the file name, e.g. ["KeePass", "Passwords.kdbx"]. */
    val filePath: List<String>,
) : SourceConfig {
    init {
        require(filePath.isNotEmpty()) { "filePath must name a file" }
    }

    override val kind: SourceKind get() = SourceKind.SMB

    override val fileName: String get() = filePath.last()

    /** Folders of [filePath] without the file name, where a source change starts browsing (DS-02). */
    val folderPath: List<String> get() = filePath.dropLast(1)

    /** UNC form for display: \\host\share\folder\file.kdbx */
    val unc: String get() = "\\\\" + (listOf(host, share) + filePath).joinToString("\\")

    /** True if [other] is an SMB source that points to the same file on the same server (SMB names are case-insensitive). */
    fun sameFileAs(other: SourceConfig?): Boolean =
        other is SmbSource &&
            host.equals(other.host, ignoreCase = true) &&
            port == other.port &&
            share.equals(other.share, ignoreCase = true) &&
            filePath.size == other.filePath.size &&
            filePath.zip(other.filePath).all { (a, b) -> a.equals(b, ignoreCase = true) }

    /** True if both address the same server (host and port). */
    fun sameServerAs(host: String, port: Int): Boolean = this.host.equals(host, ignoreCase = true) && this.port == port
}

/**
 * A file on the phone, picked with the system file picker (DS-08). The access
 * is a persisted read permission for [uri] that Android keeps for the app.
 */
data class FileSource(
    /** Document URI as text (content://...), so JVM tests need no android.net.Uri. */
    val uri: String,
    /** Name of the file as shown by the provider (DS-09). */
    val displayName: String,
    /** Where the file lies, e.g. "Interner Speicher / Syncthing / KeePass", computed once at the pick; null if unknown. */
    val place: String?,
) : SourceConfig {
    override val kind: SourceKind get() = SourceKind.FILE

    override val fileName: String get() = displayName
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
    /** Exactly one source or none (DS-08). */
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
    /**
     * Document URI of the folder the file picker should start in when no better
     * place is known, e.g. changing from SMB to a file (DS-10). Updated on every
     * pick, accepted or not. Not a secret.
     */
    val lastPickedFolder: String? = null,
)

/** Persisted settings as a flow. Updates are atomic and visible at once. */
interface SettingsRepository {
    val settings: StateFlow<KpmSettings>

    /** Applies [transform] atomically and persists the result. Returns the new settings. */
    fun update(transform: (KpmSettings) -> KpmSettings): KpmSettings
}
