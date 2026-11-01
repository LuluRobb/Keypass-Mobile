package de.lulurobb.keypassmobile.core.settings

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Decides when the master password must be typed again (EN-05).
 *
 * At every password entry the keystore key that protects the stored password
 * hash gets an end of validity: entry time plus the interval active then
 * ([keyValidityEnd]). That end cannot be extended afterwards. Therefore a
 * shorter interval chosen later applies at once, while a longer one only
 * applies from the next password entry.
 *
 * A clock that was set back before the last password entry (by more than a
 * small tolerance) makes the password due, so turning back the time cannot
 * extend the interval.
 */
class PasswordExpiry(private val clock: Clock) {

    /** End of validity for the keystore key created at [passwordEntry] with [interval]. */
    fun keyValidityEnd(passwordEntry: Instant, interval: PasswordInterval): Instant =
        passwordEntry.plus(interval.duration)

    /**
     * Instant from which the password is due: the last entry plus the shorter
     * of the interval active at that entry and the current interval.
     */
    fun dueAt(
        lastPasswordEntry: Instant,
        intervalAtEntry: PasswordInterval,
        currentInterval: PasswordInterval,
    ): Instant = lastPasswordEntry.plus(minOf(intervalAtEntry.duration, currentInterval.duration))

    /**
     * True when the master password must be typed now. Also true when there
     * was no password entry yet (null values), or when the clock is earlier
     * than the last entry by more than [CLOCK_TOLERANCE].
     */
    fun isDue(
        lastPasswordEntry: Instant?,
        intervalAtEntry: PasswordInterval?,
        currentInterval: PasswordInterval,
    ): Boolean {
        if (lastPasswordEntry == null || intervalAtEntry == null) return true
        val now = clock.instant()
        if (now.isBefore(lastPasswordEntry.minus(CLOCK_TOLERANCE))) return true
        return !now.isBefore(dueAt(lastPasswordEntry, intervalAtEntry, currentInterval))
    }

    companion object {
        /** Small backward clock corrections (e.g. by network time) are tolerated. */
        val CLOCK_TOLERANCE: Duration = Duration.ofMinutes(5)
    }
}
