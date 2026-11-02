package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability as PlatformFingerprintAvailability
import de.lulurobb.keypassmobile.platform.setup.ConnectError as PlatformConnectError
import de.lulurobb.keypassmobile.platform.setup.KdbxFileEntry
import de.lulurobb.keypassmobile.ui.settings.FingerprintAvailability
import de.lulurobb.keypassmobile.ui.setup.ConnectError
import de.lulurobb.keypassmobile.ui.setup.KdbxFile

/*
 * Mapping between the platform's settings types and the plain numbers of the
 * settings screen (days and seconds), and of setup results to the setup screens.
 */

/** The interval of [days] (1, 3, 7, 14), null for any other number. */
fun passwordIntervalOf(days: Int): PasswordInterval? = PasswordInterval.entries.firstOrNull { it.days == days }

/** The timeout of [seconds] (30, 60, 120, 300), null for any other number. */
fun idleTimeoutOf(seconds: Int): IdleTimeout? = IdleTimeout.entries.firstOrNull { it.seconds == seconds }

/** The timeout of [seconds] (15, 30, 60), null for any other number. */
fun clipboardTimeoutOf(seconds: Int): ClipboardTimeout? = ClipboardTimeout.entries.firstOrNull { it.seconds == seconds }

val IdleTimeout.seconds: Int get() = duration.seconds.toInt()

val ClipboardTimeout.seconds: Int get() = duration.seconds.toInt()

fun PlatformFingerprintAvailability.toUi(): FingerprintAvailability = when (this) {
    PlatformFingerprintAvailability.AVAILABLE -> FingerprintAvailability.Available
    PlatformFingerprintAvailability.NONE_ENROLLED -> FingerprintAvailability.NoneEnrolled
    PlatformFingerprintAvailability.NO_HARDWARE -> FingerprintAvailability.NoHardware
}

/** The server form's error for a failed connect. */
fun PlatformConnectError.toUi(): ConnectError = when (this) {
    PlatformConnectError.Unreachable -> ConnectError.Unreachable
    PlatformConnectError.LoginFailed -> ConnectError.LoginFailed
    is PlatformConnectError.OtherServer -> ConnectError.OtherServer(rememberedNameOf(change))
    PlatformConnectError.Unsupported -> ConnectError.Unsupported
    PlatformConnectError.Unknown -> ConnectError.Unknown
}

fun KdbxFileEntry.toUi(time: TimeSource): KdbxFile = KdbxFile(name, sizeBytes, time.local(modified))
