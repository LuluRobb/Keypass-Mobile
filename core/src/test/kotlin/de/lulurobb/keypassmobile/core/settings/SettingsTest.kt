package de.lulurobb.keypassmobile.core.settings

import de.lulurobb.keypassmobile.core.MutableClock
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {

    @Test
    fun valuesAndDefaults() {
        assertEquals(listOf(1, 3, 7, 14), PasswordInterval.entries.map { it.days })
        assertEquals(PasswordInterval.SEVEN_DAYS, PasswordInterval.DEFAULT)
        assertEquals(Duration.ofDays(14), PasswordInterval.FOURTEEN_DAYS.duration)

        assertEquals(listOf(30L, 60L, 120L, 300L), IdleTimeout.entries.map { it.duration.seconds })
        assertEquals(IdleTimeout.MINUTES_2, IdleTimeout.DEFAULT)

        assertEquals(Duration.ofHours(4), BACKGROUND_SYNC_INTERVAL)
    }

    @Test
    fun fromNameFallsBackToDefault() {
        assertEquals(PasswordInterval.THREE_DAYS, PasswordInterval.fromName("THREE_DAYS"))
        assertEquals(PasswordInterval.DEFAULT, PasswordInterval.fromName(null))
        assertEquals(IdleTimeout.DEFAULT, IdleTimeout.fromName("TEN_HOURS"))
    }
}

class PasswordExpiryTest {

    private val entry = Instant.parse("2026-11-01T10:00:00Z")
    private val clock = MutableClock(entry)
    private val expiry = PasswordExpiry(clock)

    private fun dueNow(atEntry: PasswordInterval?, current: PasswordInterval, last: Instant? = entry) =
        expiry.isDue(last, atEntry, current)

    @Test
    fun dueExactlyAfterTheInterval() {
        val seven = PasswordInterval.SEVEN_DAYS
        assertFalse(dueNow(seven, seven))
        clock.now = entry.plus(Duration.ofDays(7)).minusMillis(1)
        assertFalse(dueNow(seven, seven))
        clock.now = entry.plus(Duration.ofDays(7))
        assertTrue(dueNow(seven, seven))
        clock.now = entry.plus(Duration.ofDays(30))
        assertTrue(dueNow(seven, seven))
    }

    @Test
    fun shorterIntervalAppliesAtOnce() {
        clock.now = entry.plus(Duration.ofDays(2))
        assertFalse(dueNow(PasswordInterval.FOURTEEN_DAYS, PasswordInterval.FOURTEEN_DAYS))
        // User switches from 14 to 1 day: two days since the entry, so due now.
        assertTrue(dueNow(PasswordInterval.FOURTEEN_DAYS, PasswordInterval.ONE_DAY))
        assertEquals(
            entry.plus(Duration.ofDays(3)),
            expiry.dueAt(entry, PasswordInterval.FOURTEEN_DAYS, PasswordInterval.THREE_DAYS),
        )
    }

    @Test
    fun longerIntervalOnlyFromTheNextEntry() {
        // The keystore key was created with 3 days and cannot be extended.
        assertEquals(entry.plus(Duration.ofDays(3)), expiry.keyValidityEnd(entry, PasswordInterval.THREE_DAYS))
        clock.now = entry.plus(Duration.ofDays(3))
        assertTrue(dueNow(PasswordInterval.THREE_DAYS, PasswordInterval.FOURTEEN_DAYS))
        // After the next password entry with 14 days active, 14 days apply.
        val next = clock.now
        clock.now = next.plus(Duration.ofDays(13))
        assertFalse(dueNow(PasswordInterval.FOURTEEN_DAYS, PasswordInterval.FOURTEEN_DAYS, last = next))
        clock.now = next.plus(Duration.ofDays(14))
        assertTrue(dueNow(PasswordInterval.FOURTEEN_DAYS, PasswordInterval.FOURTEEN_DAYS, last = next))
    }

    @Test
    fun missingEntryIsDue() {
        assertTrue(dueNow(PasswordInterval.SEVEN_DAYS, PasswordInterval.SEVEN_DAYS, last = null))
        assertTrue(dueNow(null, PasswordInterval.SEVEN_DAYS))
    }

    @Test
    fun clockSetBackMakesThePasswordDue() {
        val seven = PasswordInterval.SEVEN_DAYS
        clock.now = entry.minus(Duration.ofMinutes(1))
        assertFalse(dueNow(seven, seven), "small corrections are tolerated")
        clock.now = entry.minus(PasswordExpiry.CLOCK_TOLERANCE)
        assertFalse(dueNow(seven, seven))
        clock.now = entry.minus(PasswordExpiry.CLOCK_TOLERANCE).minusMillis(1)
        assertTrue(dueNow(seven, seven))
        clock.now = entry.minus(Duration.ofDays(1))
        assertTrue(dueNow(seven, seven))
    }
}
