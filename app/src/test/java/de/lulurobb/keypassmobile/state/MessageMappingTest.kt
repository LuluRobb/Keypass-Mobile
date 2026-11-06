package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.sync.CandidateRejection
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.FingerprintSetupResult
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.ui.common.UiMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageMappingTest {

    @Test
    fun `every sync result has its message`() {
        assertEquals(UiMessage.SyncNoNewer, syncResultMessage(SyncResult.UpToDate))
        assertEquals(UiMessage.SyncUpdated, syncResultMessage(SyncResult.Updated))
        assertEquals(UiMessage.DatabaseLoaded, syncResultMessage(SyncResult.CandidateReady))
        assertNull(syncResultMessage(SyncResult.NewVersionRejected))
        assertNull(syncResultMessage(SyncResult.NotConfigured))
    }

    @Test
    fun `every sync problem has its message`() {
        assertEquals(UiMessage.SyncUnreachable, syncResultMessage(SyncResult.Failed(SyncProblem.UNREACHABLE)))
        assertEquals(UiMessage.SyncOtherServer, syncResultMessage(SyncResult.Failed(SyncProblem.SERVER_CHANGED)))
        for (problem in SyncProblem.entries - setOf(SyncProblem.UNREACHABLE, SyncProblem.SERVER_CHANGED)) {
            assertEquals(UiMessage.SyncFailed, syncResultMessage(SyncResult.Failed(problem)), "$problem")
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

        assertNull(unlockMessage(outcome()))
        assertEquals(UiMessage.SyncUpdated, unlockMessage(outcome(newVersion = true)))
        assertEquals(UiMessage.FingerprintRenewed(7), unlockMessage(outcome(renewedDays = 7)))
        assertEquals(UiMessage.FingerprintRenewed(7), unlockMessage(outcome(newVersion = true, renewedDays = 7)))
        assertEquals(UiMessage.FingerprintStoreFailed, unlockMessage(outcome(storeFailed = true, newVersion = true)))
    }

    @Test
    fun `a rejected candidate alone has no message, the list banner shows it`() {
        val outcome = UnlockOutcome.Unlocked(
            newVersionOpened = false,
            candidateRejected = CandidateRejection.WRONG_KEY,
            fingerprintRenewedForDays = null,
        )
        assertNull(unlockMessage(outcome))
    }

    @Test
    fun `fingerprint switch results`() {
        assertNull(fingerprintSetupMessage(FingerprintSetupResult.DISABLED))
        assertNull(fingerprintSetupMessage(FingerprintSetupResult.STORED))
        assertEquals(UiMessage.FingerprintOnNextPassword, fingerprintSetupMessage(FingerprintSetupResult.ON_NEXT_PASSWORD))
        assertEquals(UiMessage.FingerprintStoreFailed, fingerprintSetupMessage(FingerprintSetupResult.FAILED))
    }
}
