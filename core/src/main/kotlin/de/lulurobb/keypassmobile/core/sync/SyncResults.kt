package de.lulurobb.keypassmobile.core.sync

import de.lulurobb.keypassmobile.model.Vault
import java.time.Instant

/** Where the password hash for an unlock came from. */
enum class KeySource {
    /** The user typed the master password just now. */
    TYPED_PASSWORD,

    /** Stored hash released by the fingerprint (EN-03). */
    FINGERPRINT,
}

/** Result of [SyncEngine.unlock]. */
sealed interface UnlockResult {
    /**
     * The database is open. [newVersionOpened] is true when a candidate
     * (newer file from the background sync) was opened and is now the
     * offline copy. [candidateRejected] is set when a candidate could not be
     * opened and the offline copy was opened instead; the UI shows a hint
     * (EN-06). A rejected candidate with [CandidateRejection.WRONG_KEY] is kept,
     * so locking and unlocking with the new master password opens it later.
     */
    class Opened(
        val vault: Vault,
        val newVersionOpened: Boolean,
        val candidateRejected: CandidateRejection?,
    ) : UnlockResult

    /**
     * No local file opens with this key. With [KeySource.FINGERPRINT] the app
     * asks for the master password (EN-06); with a typed password it was wrong,
     * or the password was changed on the PC and the new file was not
     * downloaded yet (a sync then fetches it as candidate).
     */
    data class WrongKey(val keySource: KeySource) : UnlockResult

    /** There is no local file at all: setup or a first successful sync is needed. */
    data object NoDatabase : UnlockResult

    /**
     * The local file is damaged. The offline copy is kept but its metadata is
     * dropped, so the next sync downloads the file again.
     */
    data object Corrupt : UnlockResult
}

/** Why a candidate could not be opened at unlock. */
enum class CandidateRejection {
    /** The key does not fit the candidate. It is kept for a later unlock with a new password. */
    WRONG_KEY,

    /** The candidate was damaged and has been deleted. */
    CORRUPT,
}

/** Result of [SyncEngine.refreshAfterUnlock]. */
sealed interface RefreshResult {
    /** The open version is the one on the share. */
    data object UpToDate : RefreshResult

    /**
     * A newer version was downloaded, opened with the key in memory and has
     * replaced the offline copy (SY-05). The caller shows [vault] and closes
     * the previous one; if the app was locked meanwhile, it closes [vault].
     */
    class Updated(val vault: Vault) : RefreshResult

    /**
     * The share has a newer version that the key in memory does not open
     * (master password changed on the PC). It is kept as candidate, the
     * offline copy stays open (EN-06).
     */
    data object NewVersionRejected : RefreshResult

    /** The check or download failed; the offline copy is unchanged. */
    data class Failed(val problem: SyncProblem) : RefreshResult
}

/** Result of [SyncEngine.backgroundSync]. */
sealed interface BackgroundSyncResult {
    /** The offline copy is the version on the share. */
    data object UpToDate : BackgroundSyncResult

    /** A newer version was downloaded as candidate for the next unlock (SY-06). */
    data object CandidateDownloaded : BackgroundSyncResult

    /** The candidate already is the version on the share; nothing downloaded. */
    data object CandidateAlreadyPresent : BackgroundSyncResult

    /** The check or download failed; local files are unchanged. */
    data class Failed(val problem: SyncProblem) : BackgroundSyncResult
}

/** Problems shown in the sync status (SY-08). Persisted by name. */
enum class SyncProblem {
    UNREACHABLE,
    SERVER_CHANGED,
    AUTH_FAILED,
    NOT_FOUND,
    ACCESS_DENIED,
    REMOTE_ERROR,

    /** The download ended early or the file changed while downloading. */
    DOWNLOAD_INCOMPLETE,

    /** The downloaded file or the candidate is no KDBX file or damaged. */
    INVALID_FILE,

    /** A newer version does not open with the current key (EN-06). */
    NEW_VERSION_REJECTED,

    /** Reading or writing the local files failed. */
    LOCAL_STORAGE,
}

/** Which version is open, for SY-08. */
enum class OpenedVersion {
    /** Confirmed to be the version on the share since this unlock. */
    SHARE,

    /** The offline copy, not (yet) confirmed to be current. */
    OFFLINE_COPY,
}

/** Candidate file from the background sync (SY-06). */
enum class CandidateState {
    NONE,

    /** Waiting to be opened at the next unlock. */
    PENDING,

    /** Did not open with the key of the last unlock; kept for an unlock with a new password. */
    REJECTED,
}

/** Sync status for the settings screen and the list header (SY-08, DS-01). */
data class SyncStatus(
    /** True when an offline copy or a candidate exists, so unlocking is possible. */
    val hasLocalDatabase: Boolean,
    /** Version opened at the last unlock in this process; null before the first unlock. */
    val openedVersion: OpenedVersion?,
    /** Last time the local files were confirmed to hold the version on the share. */
    val lastSuccessfulSync: Instant?,
    /** Problem of the last sync attempt; null after a successful one. */
    val lastProblem: SyncProblem?,
    val lastProblemAt: Instant?,
    val candidate: CandidateState,
)
