package de.lulurobb.keypassmobile.ui.unlock

import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.PasswordRequiredReason
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.UnlockOffer
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.FakeAppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.testTime
import de.lulurobb.keypassmobile.state.unlockedView
import de.lulurobb.keypassmobile.ui.common.UiMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockModelTest {

    private val platform = FakeAppPlatform()
    private val messages = AppMessages()

    /** How often the field was cleared. */
    private var cleared = 0

    private fun TestScope.model(firstRun: Boolean = false, fillTarget: FillTarget? = null): UnlockModel {
        val model = UnlockModel(platform, backgroundScope, testTime(), messages, fillTarget, firstRun)
        runCurrent()
        return model
    }

    private fun TestScope.submit(model: UnlockModel, text: String) {
        model.submit(text.isEmpty(), { text.toCharArray() }, { cleared++ })
        runCurrent()
    }

    private fun TestScope.fingerprint(model: UnlockModel) {
        model.onFingerprint(platform.prompter())
        runCurrent()
    }

    private fun unlocked(
        renewedDays: Int? = null,
        storeFailed: Boolean = false,
        newVersion: Boolean = false,
    ) = UnlockOutcome.Unlocked(newVersion, null, renewedDays, storeFailed)

    private fun password(reason: PasswordRequiredReason) = UnlockOffer.Password(reason)

    // ---- offer and reasons ----

    @Test
    fun `fingerprint offer shows the fingerprint mode and asks the screen to open the prompt`() = runTest {
        platform.offer = UnlockOffer.Fingerprint
        val model = model()
        val state = model.state.value
        assertEquals(UnlockMode.Fingerprint, state.mode)
        assertTrue(state.fingerprintUsable)
        assertNull(state.passwordReason)
        assertEquals(1, model.promptToken.value)
        assertEquals(listOf("unlockOffer"), platform.calls)
    }

    @Test
    fun `password offers map every reason`() = runTest {
        platform.settingsFlow.value = KpmSettings(passwordInterval = PasswordInterval.THREE_DAYS)
        platform.fingerprintUsable = true
        val expected = mapOf(
            PasswordRequiredReason.NOT_SET_UP to Pair(null, false),
            PasswordRequiredReason.DUE to Pair(PasswordReason.Due(3), false),
            PasswordRequiredReason.FINGERPRINT_INVALIDATED to Pair(PasswordReason.FingerprintInvalidated, false),
            PasswordRequiredReason.STORED_VALUE_REJECTED to Pair(PasswordReason.StoredKeyRejected, false),
            PasswordRequiredReason.CANCELLED to Pair(null, true),
            PasswordRequiredReason.FINGERPRINT_FAILED to Pair(null, true),
        )
        for ((reason, want) in expected) {
            platform.offer = password(reason)
            val model = model()
            val state = model.state.value
            assertEquals(UnlockMode.Password, state.mode, "$reason")
            assertEquals(want.first, state.passwordReason, "$reason")
            assertEquals(want.second, state.fingerprintUsable, "$reason")
            assertEquals(0, model.promptToken.value, "$reason")
        }
    }

    @Test
    fun `the file name comes from the source and the fill target is passed on`() = runTest {
        val source = SourceConfig("nas", 445, "", "rel", "daten", listOf("KeePass", "Passwords.kdbx"))
        platform.settingsFlow.value = KpmSettings(source = source)
        val target = FillTarget.Web("example.org", "com.browser")
        val state = model(fillTarget = target).state.value
        assertEquals("Passwords.kdbx", state.fileName)
        assertEquals(target, state.fillTarget)
        assertNull(state.firstRun)
    }

    // ---- password ----

    @Test
    fun `an empty password shows the empty error and editing clears it`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        val model = model()
        submit(model, "")
        assertEquals(PasswordError.Empty, model.state.value.passwordError)
        assertTrue(platform.unlockCalls.isEmpty())
        model.onPasswordEdited()
        runCurrent()
        assertNull(model.state.value.passwordError)
    }

    @Test
    fun `a wrong password shows the error and keeps the field`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        platform.unlockOutcomes += UnlockOutcome.WrongPassword
        val model = model()
        submit(model, "falsch")
        assertEquals(PasswordError.Wrong, model.state.value.passwordError)
        assertFalse(model.state.value.busy)
        assertEquals(0, cleared)
        assertEquals(listOf(FakeAppPlatform.UnlockCall("falsch", null)), platform.unlockCalls)
        assertNull(messages.message.value)
    }

    @Test
    fun `unlocking clears the field and shows no message when nothing happened`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        platform.unlockOutcomes += unlocked()
        val model = model()
        submit(model, "richtig")
        assertEquals(1, cleared)
        assertNull(messages.message.value)
        assertFalse(model.lastFingerprintStored)
    }

    @Test
    fun `unlocking with a renewed fingerprint says for how many days`() = runTest {
        platform.offer = password(PasswordRequiredReason.DUE)
        platform.unlockOutcomes += unlocked(renewedDays = 7)
        val model = model()
        submit(model, "richtig")
        assertEquals(UiMessage.FingerprintRenewed(7), messages.message.value)
        assertTrue(model.lastFingerprintStored)
    }

    @Test
    fun `a failed fingerprint store wins over the other messages`() = runTest {
        platform.offer = password(PasswordRequiredReason.DUE)
        platform.unlockOutcomes += unlocked(storeFailed = true, newVersion = true)
        val model = model()
        submit(model, "richtig")
        assertEquals(UiMessage.FingerprintStoreFailed, messages.message.value)
        assertFalse(model.lastFingerprintStored)
    }

    @Test
    fun `a newer version opened at unlock is announced`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        platform.unlockOutcomes += unlocked(newVersion = true)
        submit(model(), "richtig")
        assertEquals(UiMessage.SyncUpdated, messages.message.value)
    }

    @Test
    fun `Busy and Aborted change nothing`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        platform.unlockOutcomes += UnlockOutcome.Busy
        platform.unlockOutcomes += UnlockOutcome.Aborted
        val model = model()
        val before = model.state.value
        submit(model, "a")
        submit(model, "b")
        assertEquals(before, model.state.value)
        assertEquals(0, cleared)
        assertNull(messages.message.value)
    }

    // ---- first run ----

    @Test
    fun `first run always asks for the password and shows the switch`() = runTest {
        platform.offer = UnlockOffer.Fingerprint
        platform.settingsFlow.value = KpmSettings(passwordInterval = PasswordInterval.FOURTEEN_DAYS)
        val model = model(firstRun = true)
        val state = model.state.value
        assertEquals(UnlockMode.Password, state.mode)
        assertFalse(state.fingerprintUsable)
        val first = state.firstRun!!
        assertEquals(14, first.passwordIntervalDays)
        assertTrue(first.fingerprintAvailable)
        assertTrue(first.fingerprintWanted)
        assertNull(first.fileSizeBytes)
        assertEquals(0, model.promptToken.value)
        assertFalse("unlockOffer" in platform.calls)
    }

    @Test
    fun `first run hands the switch to the platform`() = runTest {
        val model = model(firstRun = true)
        submit(model, "eins")
        model.onFingerprintWantedChange(false)
        runCurrent()
        assertFalse(model.state.value.firstRun!!.fingerprintWanted)
        submit(model, "zwei")
        model.onFingerprintWantedChange(true)
        submit(model, "drei")
        assertEquals(listOf(true, false, true), platform.unlockCalls.map { it.enableFingerprint })
    }

    @Test
    fun `first run without an enrolled fingerprint hides the switch and does not enable it`() = runTest {
        platform.availability = FingerprintAvailability.NONE_ENROLLED
        val model = model(firstRun = true)
        val first = model.state.value.firstRun!!
        assertFalse(first.fingerprintAvailable)
        assertFalse(first.fingerprintWanted)
        model.onFingerprintWantedChange(true)
        submit(model, "geheim")
        assertEquals(false, platform.unlockCalls.single().enableFingerprint)
    }

    @Test
    fun `later unlocks leave the switch to the platform`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        submit(model(), "geheim")
        assertNull(platform.unlockCalls.single().enableFingerprint)
    }

    @Test
    fun `first run does not announce the renewed fingerprint but keeps what the setup needs`() = runTest {
        platform.unlockOutcomes += unlocked(renewedDays = 7)
        platform.unlockOutcomes += unlocked(storeFailed = true)
        val model = model(firstRun = true)
        submit(model, "geheim")
        assertNull(messages.message.value)
        assertTrue(model.lastFingerprintStored)
        submit(model, "geheim")
        assertEquals(UiMessage.FingerprintStoreFailed, messages.message.value)
        assertFalse(model.lastFingerprintStored)
    }

    @Test
    fun `the file size comes from the setup and can be removed`() = runTest {
        val model = model(firstRun = true)
        model.setFirstRunFileSize(2048)
        runCurrent()
        assertEquals(2048L, model.state.value.firstRun!!.fileSizeBytes)
        model.setFirstRunFileSize(null)
        runCurrent()
        assertNull(model.state.value.firstRun!!.fileSizeBytes)
    }

    // ---- fingerprint ----

    @Test
    fun `a cancelled prompt switches to the password without notice and keeps the fingerprint usable`() = runTest {
        platform.offer = UnlockOffer.Fingerprint
        platform.fingerprintUsable = true
        val model = model()
        fingerprint(model)
        val state = model.state.value
        assertEquals(UnlockMode.Password, state.mode)
        assertNull(state.passwordReason)
        assertTrue(state.fingerprintUsable)
        assertNull(messages.message.value)
    }

    @Test
    fun `a failed prompt switches to the password and says so`() = runTest {
        platform.offer = UnlockOffer.Fingerprint
        platform.fingerprintUsable = true
        platform.promptOutcomes += UnlockOutcome.PasswordRequired(PasswordRequiredReason.FINGERPRINT_FAILED)
        val model = model()
        fingerprint(model)
        assertEquals(UnlockMode.Password, model.state.value.mode)
        assertEquals(UiMessage.FingerprintFailed, messages.message.value)
    }

    @Test
    fun `an invalidated key shows the notice after the prompt`() = runTest {
        platform.offer = UnlockOffer.Fingerprint
        platform.promptOutcomes += UnlockOutcome.PasswordRequired(PasswordRequiredReason.FINGERPRINT_INVALIDATED)
        val model = model()
        fingerprint(model)
        val state = model.state.value
        assertEquals(PasswordReason.FingerprintInvalidated, state.passwordReason)
        assertFalse(state.fingerprintUsable)
    }

    @Test
    fun `unlocking with the fingerprint posts the message`() = runTest {
        platform.offer = UnlockOffer.Fingerprint
        platform.promptOutcomes += unlocked(newVersion = true)
        val model = model()
        fingerprint(model)
        assertEquals(UiMessage.SyncUpdated, messages.message.value)
        assertEquals(UnlockMode.Fingerprint, model.state.value.mode)
    }

    @Test
    fun `use password clears the notice and offers the fingerprint`() = runTest {
        platform.offer = password(PasswordRequiredReason.DUE)
        val model = model()
        model.onUsePassword()
        runCurrent()
        val state = model.state.value
        assertEquals(UnlockMode.Password, state.mode)
        assertNull(state.passwordReason)
        assertTrue(state.fingerprintUsable)
    }

    // ---- problems ----

    @Test
    fun `no database shows the problem and a sync that finds the file clears it`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        platform.unlockOutcomes += UnlockOutcome.NoDatabase
        platform.syncResults += SyncResult.CandidateReady
        platform.syncGate = CompletableDeferred()
        val model = model()
        submit(model, "geheim")
        assertEquals(UnlockProblem.NoDatabase, model.state.value.problem)

        model.onSyncNow()
        runCurrent()
        assertEquals(UiMessage.SyncStarted, messages.message.value)
        assertEquals(UnlockProblem.NoDatabase, model.state.value.problem)

        platform.syncGate!!.complete(Unit)
        runCurrent()
        assertNull(model.state.value.problem)
        assertEquals(UiMessage.DatabaseLoaded, messages.message.value)
        assertEquals(1, platform.calls.count { it == "syncNow" })
    }

    @Test
    fun `a failed sync keeps the corrupt problem and the next one clears it`() = runTest {
        platform.unlockOutcomes += UnlockOutcome.Corrupt
        platform.syncResults += SyncResult.Failed(SyncProblem.UNREACHABLE)
        platform.syncResults += SyncResult.UpToDate
        val model = model()
        submit(model, "geheim")
        assertEquals(UnlockProblem.Corrupt, model.state.value.problem)

        model.onSyncNow()
        runCurrent()
        assertEquals(UnlockProblem.Corrupt, model.state.value.problem)
        assertEquals(UiMessage.SyncUnreachable, messages.message.value)

        model.onSyncNow()
        runCurrent()
        assertNull(model.state.value.problem)
        assertEquals(UiMessage.DatabaseLoaded, messages.message.value)
    }

    @Test
    fun `a sync without a source keeps the problem`() = runTest {
        platform.unlockOutcomes += UnlockOutcome.NoDatabase
        platform.syncResults += SyncResult.NotConfigured
        val model = model()
        submit(model, "geheim")
        model.onSyncNow()
        runCurrent()
        assertEquals(UnlockProblem.NoDatabase, model.state.value.problem)
        assertEquals(UiMessage.SyncStarted, messages.message.value)
    }

    @Test
    fun `a second tap while syncing does nothing`() = runTest {
        platform.unlockOutcomes += UnlockOutcome.NoDatabase
        platform.syncGate = CompletableDeferred()
        val model = model()
        submit(model, "geheim")
        model.onSyncNow()
        model.onSyncNow()
        runCurrent()
        assertEquals(1, platform.calls.count { it == "syncNow" })
    }

    // ---- busy ----

    @Test
    fun `busy while an own unlock runs and a second attempt is ignored`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        platform.unlockGate = CompletableDeferred()
        platform.unlockOutcomes += unlocked()
        val model = model()
        submit(model, "geheim")
        assertTrue(model.state.value.busy)
        submit(model, "geheim")
        fingerprint(model)
        assertEquals(1, platform.unlockCalls.size)
        assertFalse("prompt" in platform.calls)

        platform.unlockGate!!.complete(Unit)
        runCurrent()
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `busy while the other activity unlocks`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        val model = model()
        assertFalse(model.state.value.busy)
        platform.sessionFlow.value = SessionView.Unlocking
        runCurrent()
        assertTrue(model.state.value.busy)
        submit(model, "geheim")
        assertTrue(platform.unlockCalls.isEmpty())
        platform.sessionFlow.value = SessionView.Locked(null)
        runCurrent()
        assertFalse(model.state.value.busy)
    }

    // ---- reset ----

    @Test
    fun `reset after a lock asks the offer again and clears error and notice`() = runTest {
        platform.offer = password(PasswordRequiredReason.NOT_SET_UP)
        platform.unlockOutcomes += UnlockOutcome.WrongPassword
        val model = model()
        submit(model, "falsch")
        assertEquals(PasswordError.Wrong, model.state.value.passwordError)

        platform.sessionFlow.value = unlockedView()
        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        platform.offer = password(PasswordRequiredReason.DUE)
        model.reset()
        runCurrent()
        assertNull(model.state.value.passwordError)
        assertEquals(PasswordReason.Due(7), model.state.value.passwordReason)
        assertEquals(2, platform.calls.count { it == "unlockOffer" })

        platform.offer = UnlockOffer.Fingerprint
        model.reset()
        runCurrent()
        val state = model.state.value
        assertEquals(UnlockMode.Fingerprint, state.mode)
        assertNull(state.passwordReason)
        assertEquals(1, model.promptToken.value)
    }

    @Test
    fun `reset clears a problem`() = runTest {
        platform.unlockOutcomes += UnlockOutcome.NoDatabase
        val model = model()
        submit(model, "geheim")
        assertEquals(UnlockProblem.NoDatabase, model.state.value.problem)
        model.reset()
        runCurrent()
        assertNull(model.state.value.problem)
    }
}
