package de.lulurobb.keypassmobile.state

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Clock and time zone for the mapping of instants to the UI's LocalDateTime.
 * The zone is read on every use (the system zone can change), as Format.kt does.
 * Replace both in tests.
 */
class TimeSource(
    private val clock: Clock = Clock.systemUTC(),
    private val zoneOf: () -> ZoneId = { ZoneId.systemDefault() },
) {
    val zone: ZoneId get() = zoneOf()

    fun now(): Instant = clock.instant()

    /** [instant] as local date and time in the system zone. */
    fun local(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, zone)

    fun localNow(): LocalDateTime = local(now())

    fun today(): LocalDate = localNow().toLocalDate()
}
