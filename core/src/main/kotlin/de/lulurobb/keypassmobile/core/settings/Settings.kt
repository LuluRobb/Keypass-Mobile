package de.lulurobb.keypassmobile.core.settings

import java.time.Duration

/**
 * How often the master password must be typed again (EN-05), default 7 days.
 * Persist by [name], read back with [fromName].
 */
enum class PasswordInterval(val days: Int) {
    ONE_DAY(1),
    THREE_DAYS(3),
    SEVEN_DAYS(7),
    FOURTEEN_DAYS(14),
    ;

    val duration: Duration get() = Duration.ofDays(days.toLong())

    companion object {
        val DEFAULT = SEVEN_DAYS

        /** The value stored as [name], or [DEFAULT] for null or unknown names. */
        fun fromName(name: String?): PasswordInterval = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Time without use until the app locks itself (SE-02), default 2 minutes. */
enum class IdleTimeout(val duration: Duration) {
    SECONDS_30(Duration.ofSeconds(30)),
    MINUTES_1(Duration.ofMinutes(1)),
    MINUTES_2(Duration.ofMinutes(2)),
    MINUTES_5(Duration.ofMinutes(5)),
    ;

    companion object {
        val DEFAULT = MINUTES_2

        /** The value stored as [name], or [DEFAULT] for null or unknown names. */
        fun fromName(name: String?): IdleTimeout = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Time until a copied username or password is cleared from the clipboard (AF-09), default 30 seconds. */
enum class ClipboardTimeout(val duration: Duration) {
    SECONDS_15(Duration.ofSeconds(15)),
    SECONDS_30(Duration.ofSeconds(30)),
    SECONDS_60(Duration.ofSeconds(60)),
    ;

    companion object {
        val DEFAULT = SECONDS_30

        /** The value stored as [name], or [DEFAULT] for null or unknown names. */
        fun fromName(name: String?): ClipboardTimeout = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Interval of the periodic background sync (SY-06). Fixed, not a setting. */
val BACKGROUND_SYNC_INTERVAL: Duration = Duration.ofHours(4)
