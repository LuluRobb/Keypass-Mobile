package de.lulurobb.keypassmobile.ui.settings

import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.core.sync.OpenedVersion
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability as PlatformFingerprintAvailability
import de.lulurobb.keypassmobile.platform.session.FingerprintSetupResult
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.FakeAppPlatform
import de.lulurobb.keypassmobile.state.testSyncInfo
import de.lulurobb.keypassmobile.state.testTime
import de.lulurobb.keypassmobile.state.unlockedView
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.OfflineReason
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsModelTest {

    private val source = SourceConfig("nas.local", 445, "", "luca", "daten", listOf("KeePass", "pw.kdbx"))
    private val sambaGuid = UUID.fromString("74696572-6e61-2d6f-6e61-730000000000")
    private val randomGuid = UUID.fromString("9b2f6c1e-3d4a-4e5f-8a7b-0c1d2e3f4a5b")
    private val since = Instant.parse("2026-10-20T08:00:00Z")

    private val idle120 = IdleTimeout.entries.first { it.duration.seconds == 120L }
    private val idle300 = IdleTimeout.entries.first { it.duration.seconds == 300L }
    private val clipboard15 = ClipboardTimeout.entries.first { it.duration.seconds == 15L }
    private val clipboard60 = ClipboardTimeout.entries.first { it.duration.seconds == 60L }

    private val platform = FakeAppPlatform(
        settings = KpmSettings(source = source, trustedServer = TrustedServer(sambaGuid, since)),
    )
    private val messages = AppMessages()
    private val time = testTime()
    private var noSourceCalls = 0

    private fun TestScope.model(): SettingsModel =
        SettingsModel(platform, backgroundScope, time, messages) { noSourceCalls++ }.also { runCurrent() }

    private fun SettingsModel.current(): SettingsState = checkNotNull(state.value)

    @Test
    fun `maps source server and settings`() = runTest {
        platform.settingsFlow.value = platform.settingsFlow.value.copy(
            fingerprintEnabled = true,
            passwordInterval = PasswordInterval.THREE_DAYS,
            idleTimeout = idle120,
            clipboardTimeout = clipboard15,
        )
        platform.dueAt = Instant.parse("2026-11-05T08:00:00Z")
        platform.autofillSelected = true
        platform.notificationsAllowed = true
        platform.availability = PlatformFingerprintAvailability.NONE_ENROLLED

        val s = model().current()

        assertEquals(SourceInfo("nas.local", "daten", listOf("KeePass", "pw.kdbx"), "luca"), s.source)
        assertEquals(ServerIdentity("reitano-nas", LocalDate.of(2026, 10, 20), sambaGuid.toString()), s.server)
        assertIs<SyncStatus.Current>(s.sync.status)
        assertEquals(true, s.fingerprintEnabled)
        assertEquals(FingerprintAvailability.NoneEnrolled, s.fingerprintAvailability)
        assertEquals(LocalDate.of(2026, 11, 5), s.passwordDueOn)
        assertEquals(3, s.passwordIntervalDays)
        assertEquals(120, s.autoLockSeconds)
        assertEquals(15, s.clipboardSeconds)
        assertEquals(true, s.autofillSelected)
        assertEquals(true, s.notificationsAllowed)
        assertEquals("0.1.0-test", s.versionName)
        assertEquals(0, noSourceCalls)
    }

    @Test
    fun `server name falls back to the host and is null without a trusted server`() = runTest {
        platform.settingsFlow.value = platform.settingsFlow.value.copy(trustedServer = TrustedServer(randomGuid, since))
        val model = model()
        assertEquals("nas.local", model.current().server?.name)

        platform.settingsFlow.value = platform.settingsFlow.value.copy(trustedServer = null)
        runCurrent()
        assertNull(model.current().server)
    }

    @Test
    fun `sync state follows the platform and uses the unlocked session`() = runTest {
        val model = model()
        platform.syncFlow.value = testSyncInfo(
            openedVersion = OpenedVersion.OFFLINE_COPY,
            lastProblem = SyncProblem.AUTH_FAILED,
            syncing = true,
        )
        runCurrent()
        val sync = model.current().sync
        assertEquals(true, sync.syncing)
        assertEquals(OfflineReason.LoginFailed, (sync.status as SyncStatus.OfflineCopy).reason)

        platform.sessionFlow.value = unlockedView(newVersionRejected = true)
        runCurrent()
        assertEquals(OfflineReason.NewVersionRejected, (model.current().sync.status as SyncStatus.OfflineCopy).reason)
    }

    @Test
    fun `settings changes update the state and the due date`() = runTest {
        val model = model()
        assertNull(model.current().passwordDueOn)
        platform.dueAt = Instant.parse("2026-11-09T08:00:00Z")
        platform.settingsFlow.value = platform.settingsFlow.value.copy(fingerprintEnabled = true)
        runCurrent()
        assertEquals(LocalDate.of(2026, 11, 9), model.current().passwordDueOn)
    }

    @Test
    fun `refreshSystem re-reads system values`() = runTest {
        val model = model()
        assertEquals(false, model.current().autofillSelected)
        assertEquals(false, model.current().notificationsAllowed)
        assertEquals(FingerprintAvailability.Available, model.current().fingerprintAvailability)

        platform.autofillSelected = true
        platform.notificationsAllowed = true
        platform.availability = PlatformFingerprintAvailability.NO_HARDWARE
        platform.dueAt = Instant.parse("2026-11-03T23:30:00Z")
        model.refreshSystem()

        val s = model.current()
        assertEquals(true, s.autofillSelected)
        assertEquals(true, s.notificationsAllowed)
        assertEquals(FingerprintAvailability.NoHardware, s.fingerprintAvailability)
        assertEquals(LocalDate.of(2026, 11, 3), s.passwordDueOn)
    }

    @Test
    fun `system values are read at construction and by refreshSystem only`() = runTest {
        val model = model()

        // Changes of settings, session and sync do not read the system again.
        platform.autofillSelected = true
        platform.notificationsAllowed = true
        platform.availability = PlatformFingerprintAvailability.NO_HARDWARE
        platform.settingsFlow.value = platform.settingsFlow.value.copy(fingerprintEnabled = true)
        platform.syncFlow.value = testSyncInfo(syncing = true)
        platform.sessionFlow.value = unlockedView()
        runCurrent()
        model.onAutoLockChange(300)
        val stale = model.current()
        assertEquals(false, stale.autofillSelected)
        assertEquals(false, stale.notificationsAllowed)
        assertEquals(FingerprintAvailability.Available, stale.fingerprintAvailability)
        assertEquals(true, stale.fingerprintEnabled)
        assertEquals(300, stale.autoLockSeconds)

        model.refreshSystem()
        val fresh = model.current()
        assertEquals(true, fresh.autofillSelected)
        assertEquals(true, fresh.notificationsAllowed)
        assertEquals(FingerprintAvailability.NoHardware, fresh.fingerprintAvailability)
    }

    @Test
    fun `setters map the numbers`() = runTest {
        val model = model()
        model.onPasswordIntervalChange(14)
        model.onAutoLockChange(300)
        model.onClipboardChange(60)
        assertEquals(
            listOf("setPasswordInterval:FOURTEEN_DAYS", "setIdleTimeout:$idle300", "setClipboardTimeout:$clipboard60"),
            platform.calls,
        )
        val s = model.current()
        assertEquals(14, s.passwordIntervalDays)
        assertEquals(300, s.autoLockSeconds)
        assertEquals(60, s.clipboardSeconds)
    }

    @Test
    fun `unknown values are ignored`() = runTest {
        val model = model()
        model.onPasswordIntervalChange(5)
        model.onAutoLockChange(45)
        model.onClipboardChange(99)
        assertEquals(emptyList(), platform.calls)
    }

    @Test
    fun `fingerprint results map to messages`() = runTest {
        val model = model()

        platform.fingerprintSetupResult = FingerprintSetupResult.STORED
        model.onFingerprintChange(true)
        runCurrent()
        assertNull(messages.message.value)
        assertEquals(true, model.current().fingerprintEnabled)

        platform.fingerprintSetupResult = FingerprintSetupResult.ON_NEXT_PASSWORD
        model.onFingerprintChange(true)
        runCurrent()
        assertEquals(UiMessage.FingerprintOnNextPassword, messages.message.value)

        platform.fingerprintSetupResult = FingerprintSetupResult.FAILED
        model.onFingerprintChange(true)
        runCurrent()
        assertEquals(UiMessage.FingerprintStoreFailed, messages.message.value)

        messages.dismiss()
        model.onFingerprintChange(false)
        runCurrent()
        assertNull(messages.message.value)
        assertEquals(false, model.current().fingerprintEnabled)
        assertEquals("setFingerprintEnabled:false", platform.calls.last())
    }

    @Test
    fun `sync now posts the result message`() = runTest {
        val model = model()
        platform.syncResults += SyncResult.Updated
        model.onSyncNow()
        runCurrent()
        assertEquals(UiMessage.SyncUpdated, messages.message.value)

        messages.dismiss()
        platform.syncResults += SyncResult.NotConfigured
        model.onSyncNow()
        runCurrent()
        assertNull(messages.message.value)
        assertEquals(listOf("syncNow", "syncNow"), platform.calls)
    }

    @Test
    fun `no source calls onNoSource once and shows no state`() = runTest {
        platform.settingsFlow.value = KpmSettings()
        val model = model()
        assertNull(model.state.value)
        assertEquals(1, noSourceCalls)

        platform.settingsFlow.value = KpmSettings(fingerprintEnabled = true)
        runCurrent()
        assertEquals(1, noSourceCalls)
    }
}
