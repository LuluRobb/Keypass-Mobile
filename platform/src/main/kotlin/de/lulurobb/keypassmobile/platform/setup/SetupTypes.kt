package de.lulurobb.keypassmobile.platform.setup

import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.ServerChange
import de.lulurobb.keypassmobile.platform.session.SyncResult
import java.time.Instant
import java.util.UUID

/** What the server form collects (DS-02). The password is passed separately as CharArray. */
data class ServerAddress(
    val host: String,
    val user: String,
    val port: Int = 445,
    /** Under "Erweitert", usually empty. */
    val domain: String = "",
)

/** The server after connecting (BrowseScreen's ConnectedServer). */
data class ServerInfo(
    /** Name decoded from a Samba GUID (e.g. "reitano-nas"), else the host as entered. */
    val name: String,
    val guid: UUID,
    /** Negotiated dialect, e.g. "SMB 3.1.1". */
    val dialect: String,
    val encrypted: Boolean,
)

data class ShareEntry(val name: String, val comment: String)

data class KdbxFileEntry(val name: String, val sizeBytes: Long, val modified: Instant)

/** Result of [SourceSetup.connect]. */
sealed interface ConnectResult {
    /** Logged in. [shares] null: the server does not list its shares; offer the manual entry (DS-02). */
    data class Connected(val server: ServerInfo, val shares: List<ShareEntry>?) : ConnectResult

    data class Failed(val error: ConnectError) : ConnectResult
}

/** Why connecting failed (the server screen's ConnectError). Never carries server texts or secrets. */
sealed interface ConnectError {
    /** No SMB answer within the timeout (DS-05). */
    data object Unreachable : ConnectError

    /** Wrong user or password. */
    data object LoginFailed : ConnectError

    /**
     * A different server than the remembered one answered under this host
     * (DS-04); no login was attempted. Only for the host of the current
     * source. Accepting: connect again with acceptServerGuid = [change].newGuid.
     */
    data class OtherServer(val change: ServerChange) : ConnectError

    /** No common dialect (e.g. only SMB 1) or a protocol error. */
    data object Unsupported : ConnectError

    data object Unknown : ConnectError
}

/** Result of [SourceSetup.listFolder]. */
sealed interface FolderResult {
    /**
     * Folders and .kdbx files, sorted. System folders (#recycle, @eaDir,
     * $RECYCLE.BIN, names starting with ".", "$" or "@") are left out without
     * being counted. [hiddenCount] is the number of other files in the folder
     * that are not shown because they are no .kdbx files: "%d andere Dateien
     * sind ausgeblendet".
     */
    data class Listed(val folders: List<String>, val files: List<KdbxFileEntry>, val hiddenCount: Int) : FolderResult

    data class Failed(val error: BrowseError) : FolderResult
}

enum class BrowseError {
    /** The share does not exist (also for a manually typed name). UI: ShareNotFound. */
    SHARE_NOT_FOUND,

    /** The folder does not exist (any more). */
    NOT_FOUND,

    /** The user may not read the share or folder. UI: ShareDenied. */
    ACCESS_DENIED,

    UNREACHABLE,

    /** connect() was not called or failed. */
    NOT_CONNECTED,

    OTHER,
}

/** Result of [SourceSetup.chooseFile]. */
sealed interface ChooseResult {
    /**
     * The source is saved and the file was downloaded. [fileSizeBytes] for
     * "Vom Share geladen, ..."; [fileChanged] false when the same file was
     * chosen again. [sync]: locked [SyncResult.CandidateReady] (the next
     * typed-password unlock opens it), unlocked [SyncResult.Updated] (shown
     * now) or [SyncResult.NewVersionRejected] (other master password), or
     * [SyncResult.UpToDate]. When the file or server changed and the new file
     * waits as candidate (CandidateReady, NewVersionRejected), the old offline
     * copy is not opened any more, and an unlocked session was locked with
     * LockReason.SourceChanged: unlock with the master password of the new file.
     *
     * [lockedForNewDatabase]: the switch itself locked the session because the new
     * file does not open with the key in memory (DS-13). The UI then shows only the
     * lock notice and not "Quelle gespeichert.".
     */
    data class Saved(
        val fileSizeBytes: Long,
        val fileChanged: Boolean,
        val sync: SyncResult,
        val lockedForNewDatabase: Boolean = false,
    ) : ChooseResult

    /** Nothing was saved; the previous source stays. */
    data class Failed(val problem: SyncProblem) : ChooseResult

    /**
     * Nothing was saved and nothing is to be shown: the change was started while the app was
     * unlocked and the app locked before it was committed (D10). The previous source,
     * password and offline copy are as before; a permission taken for the new file is given back.
     */
    data object Discarded : ChooseResult
}

/**
 * A file on the phone that the user picked, after the silent check passed
 * (DS-11, DS-12). Size and modification time are null when the provider does not
 * report them.
 */
data class PickedFile(
    /** Document URI as text (content://...). */
    val uri: String,
    val displayName: String,
    val sizeBytes: Long?,
    val lastModified: Instant?,
    /** Where the file lies, e.g. "Interner Speicher / Syncthing / KeePass"; null if unknown. */
    val place: String?,
)

/**
 * Result of [FileSourceSetup.accept]. Anything but [Ok] has already released the
 * persisted read permission again (unless it is the current source's own).
 * [fileName] is the name shown in the error message.
 */
sealed interface FileCheck {
    data class Ok(val file: PickedFile) : FileCheck

    /** The file does not start like a KeePass database (DS-11). */
    data class NotKdbx(val fileName: String) : FileCheck

    /** The file was not found or could not be read (DS-11). */
    data class Unreadable(val fileName: String) : FileCheck

    /** Android refused the lasting read permission (DS-12). */
    data class NoLastingAccess(val fileName: String) : FileCheck
}

/**
 * The picked file is older than the version that is open now (DS-14).
 * [fileModified] is the modification time of the picked file, [openModified]
 * the one of the file the open version was read from.
 */
data class OlderVersion(
    val fileName: String,
    val fileModified: Instant,
    val openModified: Instant,
)
