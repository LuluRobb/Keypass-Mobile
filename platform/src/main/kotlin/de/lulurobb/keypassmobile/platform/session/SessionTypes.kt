package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.index.EntryIndex
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.sync.CandidateRejection
import de.lulurobb.keypassmobile.core.sync.KeySource
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.core.sync.SyncStatus
import de.lulurobb.keypassmobile.model.Vault
import java.util.UUID

/** Process-wide lock state (SE-01 to SE-04). Nothing of it is persisted. */
sealed interface SessionState {
    /** Locked. [reason] says why it was locked last (for "Gesperrt nach 2 Minuten ..."); null at start and after a failed unlock. */
    class Locked(val reason: LockReason?) : SessionState {
        override fun toString(): String = "Locked($reason)"
    }

    /** Decrypting: the unlock screen shows the busy state. */
    data object Unlocking : SessionState

    /**
     * The database is open. [vault] and [index] are replaced when a newer
     * version from the share was opened in the background (SY-04); [version]
     * then increases. Never keep references to them beyond the current screen.
     */
    class Unlocked internal constructor(
        val vault: Vault,
        val index: EntryIndex,
        /** How this unlock happened. */
        val keySource: KeySource,
        /** The unlock opened a newer file from the background sync (SY-06). Show "Neue Version geöffnet" once. */
        val newVersionOpened: Boolean,
        /**
         * The share has a newer file that does not open with this key (EN-06):
         * the offline copy is open. Shown as list banner until a lock.
         */
        val newVersionRejected: Boolean,
        /** Increases with every vault replacement during this unlock. */
        val version: Int,
    ) : SessionState {
        internal fun with(vault: Vault, index: EntryIndex) =
            Unlocked(vault, index, keySource, newVersionOpened, newVersionRejected = false, version = version + 1)

        internal fun rejected() = Unlocked(vault, index, keySource, newVersionOpened, newVersionRejected = true, version)

        /** The share has the open version again: the banner of a rejected newer file ends. */
        internal fun rejectionCleared() = Unlocked(vault, index, keySource, newVersionOpened, newVersionRejected = false, version)

        override fun toString(): String = "Unlocked($keySource, v$version)"
    }
}

/** Why the session was locked. */
sealed interface LockReason {
    /** "Sperren" in the app. */
    data object User : LockReason

    /** "Sperren" in the notification (SE-03). */
    data object Notification : LockReason

    /** No use for [timeout] (SE-02). UI message "Gesperrt nach ... ohne Nutzung." */
    data class Idle(val timeout: IdleTimeout) : LockReason

    /** The app was swiped away from the recents (SE-01). */
    data object TaskRemoved : LockReason

    /**
     * Android refused the foreground service (SE-03), e.g. started from the
     * background: lock on swipe-away and the notification button would not
     * work, so the session was locked right away. UI: ask to unlock again with
     * the app in the foreground.
     */
    data object ServiceRefused : LockReason

    /**
     * The source was changed (settings) to another file or share (SMB or a file on
     * the phone) whose file the key of the open vault does not open. The old vault
     * was locked so that no old data is shown or autofilled; unlock with the master
     * password of the new file.
     */
    data object SourceChanged : LockReason
}

/** Why the master password is needed instead of the fingerprint. */
enum class PasswordRequiredReason {
    /** Fingerprint off or nothing stored: plain password mode without notice. */
    NOT_SET_UP,

    /** The interval ran out (EN-05). UI: PasswordReason.Due with the current interval. The stored value was deleted. */
    DUE,

    /** A fingerprint was enrolled; the key is invalid (EN-04). The stored value was deleted. */
    FINGERPRINT_INVALIDATED,

    /** The stored value no longer opens the file, e.g. password changed on the PC (EN-06). It was deleted. */
    STORED_VALUE_REJECTED,

    /** "Passwort eingeben", back or dismissed: password mode without notice; the fingerprint stays usable. */
    CANCELLED,

    /** Too many attempts or a sensor or keystore error. UI message: FingerprintFailed. */
    FINGERPRINT_FAILED,
}

/** What the unlock screen offers first (EN-02). */
sealed interface UnlockOffer {
    /** Open the fingerprint prompt right away. */
    data object Fingerprint : UnlockOffer

    /** Show the password field; [reason] selects the notice. */
    data class Password(val reason: PasswordRequiredReason) : UnlockOffer
}

/** Result of an unlock attempt. */
sealed interface UnlockOutcome {
    /**
     * The database is open (state [SessionState.Unlocked]). [candidateRejected]
     * set: a newer file did not open, the offline copy is open (EN-06).
     * [fingerprintRenewedForDays] is set after a typed password with the
     * fingerprint on once the hash was stored for this many days (EN-05);
     * show "Fingerabdruck ist wieder für ... aktiv" or "Eingerichtet ...".
     * [fingerprintStoreFailed] is true when it should have been stored but
     * storing failed (e.g. no fingerprint enrolled): then
     * [fingerprintRenewedForDays] is null, the fingerprint stays unavailable
     * until the next typed password, and the UI must not claim it is active.
     */
    data class Unlocked(
        val newVersionOpened: Boolean,
        val candidateRejected: CandidateRejection?,
        val fingerprintRenewedForDays: Int?,
        val fingerprintStoreFailed: Boolean = false,
    ) : UnlockOutcome

    /** The typed password opens no local file (EN-01). */
    data object WrongPassword : UnlockOutcome

    /** The fingerprint path cannot be used now; switch to password mode with [reason]. */
    data class PasswordRequired(val reason: PasswordRequiredReason) : UnlockOutcome

    /** No offline copy and no candidate: setup or a first sync is needed. */
    data object NoDatabase : UnlockOutcome

    /** The local file is damaged; the next sync downloads it again. */
    data object Corrupt : UnlockOutcome

    /** Another unlock is running; nothing was done. */
    data object Busy : UnlockOutcome

    /**
     * Locked again (e.g. swiped away, or the foreground service was refused)
     * while decrypting or right after opening; nothing is shown. The reason is in
     * [SessionState.Locked].
     */
    data object Aborted : UnlockOutcome
}

/** Result of "Jetzt synchronisieren" (SY-07), the refresh after unlock (SY-04) and the background sync (SY-06). */
sealed interface SyncResult {
    /** The open version or the offline copy is the one on the share. UI: SyncNoNewer. */
    data object UpToDate : SyncResult

    /** Unlocked: a newer version was opened and is shown now. UI: SyncUpdated. */
    data object Updated : SyncResult

    /**
     * Locked: a newer version waits as candidate for the next unlock. Also when
     * the session was locked while a newer version was just opened: it waits
     * for the next unlock.
     */
    data object CandidateReady : SyncResult

    /** A newer version does not open with the key in memory (EN-06). */
    data object NewVersionRejected : SyncResult

    /**
     * Check or download failed; local files unchanged. SERVER_CHANGED: see
     * [SyncInfo.serverChange]. INVALID_FILE is also reported when a newer version
     * opened but could not be prepared for display; the offline copy and the open
     * vault stay as they were and the next sync tries again.
     */
    data class Failed(val problem: SyncProblem) : SyncResult

    /** No source configured yet. */
    data object NotConfigured : SyncResult
}

/** What started a sync run, see [SyncReport]. */
enum class SyncTrigger {
    /** The automatic check right after an unlock (SY-04). */
    AFTER_UNLOCK,

    /** "Jetzt synchronisieren" (SY-07) or accepting a new server identity (DS-04). */
    REQUESTED,

    /** The periodic background sync (SY-06). */
    SCHEDULED,

    /** The test download of a source setup or source change (DS-02). */
    SETUP,
}

/**
 * The result of one finished sync run and what started it. [id] grows with
 * every run in this process (starting at 1), so a report is shown once even
 * when two runs have the same result.
 */
data class SyncReport(val id: Long, val trigger: SyncTrigger, val result: SyncResult)

/**
 * The server answered with a different GUID than remembered (DS-04). Nothing
 * was sent to it. The UI asks once; accepting stores [newGuid].
 */
data class ServerChange(
    val host: String,
    val rememberedGuid: UUID?,
    /** Name decoded from the remembered GUID (Samba), null when not derivable. */
    val rememberedName: String?,
    val newGuid: UUID,
    val newName: String?,
)

/** Sync status for the list chip, banners and the settings (SY-08, DS-01). */
data class SyncInfo(
    val status: SyncStatus,
    /** A check or download runs right now. */
    val syncing: Boolean,
    /** Pending server identity question (DS-04), null when none. */
    val serverChange: ServerChange?,
)

/** Result of switching the fingerprint on or off in the settings. */
enum class FingerprintSetupResult {
    /** Off; the stored value was deleted. */
    DISABLED,

    /** On and stored now (unlocked, last password entry still within its interval). */
    STORED,

    /** On; the value is stored at the next master password entry. */
    ON_NEXT_PASSWORD,

    /** Storing failed (e.g. no fingerprint enrolled); the switch stays on, nothing stored. */
    FAILED,
}
