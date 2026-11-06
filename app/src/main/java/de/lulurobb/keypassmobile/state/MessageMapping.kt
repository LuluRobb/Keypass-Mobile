package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.FingerprintSetupResult
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.ui.common.UiMessage

/*
 * Mapping of platform results to snackbar messages. Every `when` is exhaustive
 * on purpose (no else): a new platform variant must fail to compile here.
 */

/** Message for a finished sync run; null when nothing is said (the list banner or the screen already shows it). */
fun syncResultMessage(result: SyncResult): UiMessage? = when (result) {
    SyncResult.UpToDate -> UiMessage.SyncNoNewer
    SyncResult.Updated -> UiMessage.SyncUpdated
    SyncResult.CandidateReady -> UiMessage.DatabaseLoaded
    // The list banner shows it.
    SyncResult.NewVersionRejected -> null
    is SyncResult.Failed -> when (result.problem) {
        SyncProblem.UNREACHABLE -> UiMessage.SyncUnreachable
        SyncProblem.SERVER_CHANGED -> UiMessage.SyncOtherServer
        SyncProblem.AUTH_FAILED,
        SyncProblem.NOT_FOUND,
        SyncProblem.ACCESS_DENIED,
        SyncProblem.REMOTE_ERROR,
        SyncProblem.DOWNLOAD_INCOMPLETE,
        SyncProblem.INVALID_FILE,
        SyncProblem.NEW_VERSION_REJECTED,
        SyncProblem.LOCAL_STORAGE,
        -> UiMessage.SyncFailed
    }
    SyncResult.NotConfigured -> null
}

/** Message shown on the unlock screen after a lock; null for no reason and for the swipe-away (the app is gone). */
fun lockMessage(reason: LockReason?): UiMessage? = when (reason) {
    null -> null
    is LockReason.Idle -> UiMessage.AutoLocked(reason.timeout.duration.seconds.toInt())
    LockReason.User,
    LockReason.Notification,
    -> UiMessage.Locked
    LockReason.TaskRemoved -> null
    LockReason.ServiceRefused -> UiMessage.LockedNoService
    LockReason.SourceChanged -> UiMessage.LockedSourceChanged
}

/**
 * Message after a successful unlock, one at most. Order: a failed fingerprint
 * store (the user must know), the renewed fingerprint, then a new version
 * opened at unlock.
 */
fun unlockMessage(outcome: UnlockOutcome.Unlocked): UiMessage? {
    val renewedDays = outcome.fingerprintRenewedForDays
    return when {
        outcome.fingerprintStoreFailed -> UiMessage.FingerprintStoreFailed
        renewedDays != null -> UiMessage.FingerprintRenewed(renewedDays)
        outcome.newVersionOpened -> UiMessage.SyncUpdated
        else -> null
    }
}

/** Message after switching the fingerprint in the settings. */
fun fingerprintSetupMessage(result: FingerprintSetupResult): UiMessage? = when (result) {
    FingerprintSetupResult.DISABLED,
    FingerprintSetupResult.STORED,
    -> null
    FingerprintSetupResult.ON_NEXT_PASSWORD -> UiMessage.FingerprintOnNextPassword
    FingerprintSetupResult.FAILED -> UiMessage.FingerprintStoreFailed
}
