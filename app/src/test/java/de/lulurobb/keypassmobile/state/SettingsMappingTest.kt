package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability as PlatformFingerprintAvailability
import de.lulurobb.keypassmobile.platform.setup.ConnectError as PlatformConnectError
import de.lulurobb.keypassmobile.platform.setup.KdbxFileEntry
import de.lulurobb.keypassmobile.ui.settings.FingerprintAvailability
import de.lulurobb.keypassmobile.ui.settings.SettingsState
import de.lulurobb.keypassmobile.ui.setup.ConnectError
import de.lulurobb.keypassmobile.ui.setup.KdbxFile
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsMappingTest {

    @Test
    fun `password interval round trips over the days of the screen`() {
        for (interval in PasswordInterval.entries) {
            assertEquals(interval, passwordIntervalOf(interval.days))
        }
        assertEquals(PasswordInterval.entries.map { it.days }, SettingsState.PasswordIntervals)
        assertNull(passwordIntervalOf(2))
    }

    @Test
    fun `idle timeout round trips over the seconds of the screen`() {
        for (timeout in IdleTimeout.entries) {
            assertEquals(timeout, idleTimeoutOf(timeout.seconds))
        }
        assertEquals(listOf(30, 60, 120, 300), IdleTimeout.entries.map { it.seconds })
        assertEquals(IdleTimeout.entries.map { it.seconds }, SettingsState.AutoLockTimes)
        assertNull(idleTimeoutOf(45))
    }

    @Test
    fun `clipboard timeout round trips over the seconds of the screen`() {
        for (timeout in ClipboardTimeout.entries) {
            assertEquals(timeout, clipboardTimeoutOf(timeout.seconds))
        }
        assertEquals(listOf(15, 30, 60), ClipboardTimeout.entries.map { it.seconds })
        assertEquals(ClipboardTimeout.entries.map { it.seconds }, SettingsState.ClipboardTimes)
        assertNull(clipboardTimeoutOf(20))
    }

    @Test
    fun `fingerprint availability maps one to one`() {
        assertEquals(FingerprintAvailability.Available, PlatformFingerprintAvailability.AVAILABLE.toUi())
        assertEquals(FingerprintAvailability.NoneEnrolled, PlatformFingerprintAvailability.NONE_ENROLLED.toUi())
        assertEquals(FingerprintAvailability.NoHardware, PlatformFingerprintAvailability.NO_HARDWARE.toUi())
    }

    @Test
    fun `connect errors map to the server form errors`() {
        assertEquals(ConnectError.Unreachable, PlatformConnectError.Unreachable.toUi())
        assertEquals(ConnectError.LoginFailed, PlatformConnectError.LoginFailed.toUi())
        assertEquals(ConnectError.Unsupported, PlatformConnectError.Unsupported.toUi())
        assertEquals(ConnectError.Unknown, PlatformConnectError.Unknown.toUi())
    }

    @Test
    fun `other server names the remembered server`() {
        val named = PlatformConnectError.OtherServer(testServerChange(rememberedName = "reitano-nas"))
        assertEquals(ConnectError.OtherServer("reitano-nas"), named.toUi())

        val change = testServerChange(rememberedName = null)
        assertEquals(
            ConnectError.OtherServer(change.rememberedGuid.toString()),
            PlatformConnectError.OtherServer(change).toUi(),
        )

        val bare = testServerChange(host = "nas.local", rememberedName = null, rememberedGuid = null)
        assertEquals(ConnectError.OtherServer("nas.local"), PlatformConnectError.OtherServer(bare).toUi())
    }

    @Test
    fun `file entries are shown in the system zone`() {
        val entry = KdbxFileEntry("Passwords.kdbx", 265_216L, Instant.parse("2026-10-31T20:38:00Z"))
        assertEquals(
            KdbxFile("Passwords.kdbx", 265_216L, LocalDateTime.of(2026, 10, 31, 20, 38)),
            entry.toUi(testTime()),
        )
        assertEquals(
            KdbxFile("Passwords.kdbx", 265_216L, LocalDateTime.of(2026, 10, 31, 21, 38)),
            entry.toUi(testTime(zone = ZoneId.of("Europe/Berlin"))),
        )
    }
}
