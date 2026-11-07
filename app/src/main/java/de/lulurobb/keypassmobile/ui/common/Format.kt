package de.lulurobb.keypassmobile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.model.FillTarget
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/* Display formatting shared by the screens. German formats only (UI-04). */

private val GERMAN = Locale.GERMANY
private val TIME = DateTimeFormatter.ofPattern("HH:mm", GERMAN)
private val DAY_MONTH = DateTimeFormatter.ofPattern("dd.MM.", GERMAN)
private val DAY_MONTH_TIME = DateTimeFormatter.ofPattern("dd.MM., HH:mm", GERMAN)
private val FULL_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", GERMAN)
private val FULL_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", GERMAN)

/**
 * Name of the app the user is shown: its visible label (resolved by :autofill with PackageManager),
 * the package name when the label could not be read.
 */
fun FillTarget.App.displayName(): String = appLabel ?: packageName

/** "21:40" for today, "28.10., 08:12" for other days. */
fun formatStamp(time: LocalDateTime, today: LocalDate = LocalDate.now()): String =
    if (time.toLocalDate() == today) time.format(TIME) else time.format(DAY_MONTH_TIME)

/** "28.10." */
fun formatDayMonth(date: LocalDate): String = date.format(DAY_MONTH)

/** "31.10.2026" */
fun formatDate(date: LocalDate): String = date.format(FULL_DATE)

/** "31.10.2026, 21:38" (file dates in the file browser). */
fun formatDateTime(time: LocalDateTime): String = time.format(FULL_DATE_TIME)

/** "259 KB", "1,4 MB" (1024 based, as file managers show it). */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> String.format(GERMAN, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** "heute um 21:40" or "am 28.10., 08:12" (settings sync text). */
@Composable
fun relativeStamp(time: LocalDateTime, today: LocalDate = LocalDate.now()): String =
    if (time.toLocalDate() == today) {
        stringResource(R.string.today_at, time.format(TIME))
    } else {
        stringResource(R.string.date_at, time.format(DAY_MONTH_TIME))
    }

/**
 * The time after "Offline-Kopie": "von heute, 21:40" for today, "vom 28.10., 08:12" for other days
 * (a bare "vom 21:40" is not German).
 */
@Composable
fun sinceStamp(time: LocalDateTime, today: LocalDate = LocalDate.now()): String =
    if (time.toLocalDate() == today) {
        stringResource(R.string.since_today, time.format(TIME))
    } else {
        stringResource(R.string.since_date, time.format(DAY_MONTH_TIME))
    }

/** "30 Sekunden", "1 Minute", "2 Minuten". */
@Composable
fun durationText(seconds: Int): String =
    if (seconds < 60) {
        pluralStringResource(R.plurals.duration_seconds, seconds, seconds)
    } else {
        val minutes = seconds / 60
        pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    }

/** "jeden Tag", "alle 7 Tage". */
@Composable
fun everyDaysText(days: Int): String = pluralStringResource(R.plurals.every_n_days, days, days)

/** "1 Tag", "7 Tage". */
@Composable
fun daysText(days: Int): String = pluralStringResource(R.plurals.duration_days, days, days)

/**
 * Host of a URL for display: scheme, user info, port, path and a leading
 * "www." removed. "https://www.amazon.de/x" gives "amazon.de". Works on
 * plain text without a scheme too, as KeePass URLs are free text.
 */
fun displayHost(url: String): String {
    var s = url.trim()
    val scheme = s.indexOf("://")
    if (scheme >= 0) s = s.substring(scheme + 3)
    val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (end >= 0) s = s.substring(0, end)
    val at = s.lastIndexOf('@')
    if (at >= 0) s = s.substring(at + 1)
    if (!s.startsWith("[")) {
        val colon = s.indexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
    }
    return s.removePrefix("www.").lowercase(GERMAN)
}

/** True for a URL that uses plain HTTP (no autofill request from Chromium browsers). */
fun isPlainHttp(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

/**
 * Identifier for display that wraps only after one of [separators] (path
 * backslashes, GUID hyphens, package dots), never inside a segment. Adds a
 * zero-width space after each separator; leading separators stay together.
 */
fun breakAfter(text: String, separators: String): String {
    val firstContent = text.indexOfFirst { it !in separators }.let { if (it < 0) text.length else it }
    val out = StringBuilder(text.length + 8)
    text.forEachIndexed { i, c ->
        out.append(c)
        when {
            // Word joiner: no break inside or right after a leading "\\".
            i < firstContent -> out.append('⁠')
            c in separators && i < text.lastIndex -> out.append('​')
        }
    }
    return out.toString()
}

/** Group path for display: "Finance / Business"; the root group shows its own name. */
fun groupLabel(groupPath: List<String>, rootGroupName: String): String =
    if (groupPath.isEmpty()) rootGroupName else groupPath.joinToString(" / ")
