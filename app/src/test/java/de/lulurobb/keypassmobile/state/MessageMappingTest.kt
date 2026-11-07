package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.sync.CandidateRejection
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.FingerprintSetupResult
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.ui.common.UiMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageMappingTest {

    @Test
    fun `every sync result has its message`() {
        assertEquals(UiMessage.SyncNoNewer, syncResultMessage(SyncResult.UpToDate, SourceKind.SMB))
        assertEquals(UiMessage.SyncUpdated, syncResultMessage(SyncResult.Updated, SourceKind.SMB))
        assertEquals(UiMessage.DatabaseLoaded, syncResultMessage(SyncResult.CandidateReady, SourceKind.SMB))
        assertNull(syncResultMessage(SyncResult.NewVersionRejected, SourceKind.SMB))
        assertNull(syncResultMessage(SyncResult.NotConfigured, SourceKind.SMB))
    }

    @Test
    fun `every sync result of a file has its message`() {
        assertEquals(UiMessage.SyncFileUnchanged, syncResultMessage(SyncResult.UpToDate, SourceKind.FILE))
        assertEquals(UiMessage.SyncUpdatedFile, syncResultMessage(SyncResult.Updated, SourceKind.FILE))
        assertEquals(UiMessage.DatabaseLoadedFile, syncResultMessage(SyncResult.CandidateReady, SourceKind.FILE))
        assertNull(syncResultMessage(SyncResult.NewVersionRejected, SourceKind.FILE))
        assertNull(syncResultMessage(SyncResult.NotConfigured, SourceKind.FILE))
    }

    @Test
    fun `every sync problem of a file has its message`() {
        assertEquals(UiMessage.SyncFileNotFound, syncResultMessage(SyncResult.Failed(SyncProblem.NOT_FOUND), SourceKind.FILE))
        assertEquals(UiMessage.SyncFileNoAccess, syncResultMessage(SyncResult.Failed(SyncProblem.ACCESS_DENIED), SourceKind.FILE))
        for (problem in SyncProblem.entries - setOf(SyncProblem.NOT_FOUND, SyncProblem.ACCESS_DENIED)) {
            assertEquals(UiMessage.SyncFailed, syncResultMessage(SyncResult.Failed(problem), SourceKind.FILE), "$problem")
        }
    }

    @Test
    fun `the SMB messages of the problems NOT_FOUND and ACCESS_DENIED stay generic`() {
        assertEquals(UiMessage.SyncFailed, syncResultMessage(SyncResult.Failed(SyncProblem.NOT_FOUND), SourceKind.SMB))
        assertEquals(UiMessage.SyncFailed, syncResultMessage(SyncResult.Failed(SyncProblem.ACCESS_DENIED), SourceKind.SMB))
    }

    @Test
    fun `database loaded and updated messages by source kind`() {
        assertEquals(UiMessage.DatabaseLoaded, databaseLoadedMessage(SourceKind.SMB))
        assertEquals(UiMessage.DatabaseLoadedFile, databaseLoadedMessage(SourceKind.FILE))
        assertEquals(UiMessage.SyncUpdated, syncUpdatedMessage(SourceKind.SMB))
        assertEquals(UiMessage.SyncUpdatedFile, syncUpdatedMessage(SourceKind.FILE))
    }

    @Test
    fun `every sync problem has its message`() {
        assertEquals(UiMessage.SyncUnreachable, syncResultMessage(SyncResult.Failed(SyncProblem.UNREACHABLE), SourceKind.SMB))
        assertEquals(UiMessage.SyncOtherServer, syncResultMessage(SyncResult.Failed(SyncProblem.SERVER_CHANGED), SourceKind.SMB))
        for (problem in SyncProblem.entries - setOf(SyncProblem.UNREACHABLE, SyncProblem.SERVER_CHANGED)) {
            assertEquals(UiMessage.SyncFailed, syncResultMessage(SyncResult.Failed(problem), SourceKind.SMB), "$problem")
        }
    }

    @Test
    fun `every lock reason has its message`() {
        assertNull(lockMessage(null))
        assertEquals(UiMessage.Locked, lockMessage(LockReason.User))
        assertEquals(UiMessage.Locked, lockMessage(LockReason.Notification))
        assertNull(lockMessage(LockReason.TaskRemoved))
        assertEquals(UiMessage.LockedNoService, lockMessage(LockReason.ServiceRefused))
        assertEquals(UiMessage.LockedSourceChanged, lockMessage(LockReason.SourceChanged))
    }

    @Test
    fun `idle lock names the timeout in seconds`() {
        for (timeout in IdleTimeout.entries) {
            assertEquals(
                UiMessage.AutoLocked(timeout.duration.seconds.toInt()),
                lockMessage(LockReason.Idle(timeout)),
            )
        }
        assertEquals(UiMessage.AutoLocked(120), lockMessage(LockReason.Idle(IdleTimeout.MINUTES_2)))
    }

    @Test
    fun `unlock message prefers the failed fingerprint store, then the renewal, then the new version`() {
        fun outcome(
            newVersion: Boolean = false,
            renewedDays: Int? = null,
            storeFailed: Boolean = false,
        ) = UnlockOutcome.Unlocked(newVersion, candidateRejected = null, fingerprintRenewedForDays = renewedDays, fingerprintStoreFailed = storeFailed)

        val smb = SourceKind.SMB
        assertNull(unlockMessage(outcome(), smb))
        assertEquals(UiMessage.SyncUpdated, unlockMessage(outcome(newVersion = true), smb))
        assertEquals(UiMessage.SyncUpdatedFile, unlockMessage(outcome(newVersion = true), SourceKind.FILE))
        assertEquals(UiMessage.FingerprintRenewed(7), unlockMessage(outcome(renewedDays = 7), smb))
        assertEquals(UiMessage.FingerprintRenewed(7), unlockMessage(outcome(newVersion = true, renewedDays = 7), smb))
        assertEquals(UiMessage.FingerprintStoreFailed, unlockMessage(outcome(storeFailed = true, newVersion = true), smb))
    }

    @Test
    fun `a rejected candidate alone has no message, the list banner shows it`() {
        val outcome = UnlockOutcome.Unlocked(
            newVersionOpened = false,
            candidateRejected = CandidateRejection.WRONG_KEY,
            fingerprintRenewedForDays = null,
        )
        assertNull(unlockMessage(outcome, SourceKind.SMB))
        assertNull(unlockMessage(outcome, SourceKind.FILE))
    }

    @Test
    fun `fingerprint switch results`() {
        assertNull(fingerprintSetupMessage(FingerprintSetupResult.DISABLED))
        assertNull(fingerprintSetupMessage(FingerprintSetupResult.STORED))
        assertEquals(UiMessage.FingerprintOnNextPassword, fingerprintSetupMessage(FingerprintSetupResult.ON_NEXT_PASSWORD))
        assertEquals(UiMessage.FingerprintStoreFailed, fingerprintSetupMessage(FingerprintSetupResult.FAILED))
    }
}
