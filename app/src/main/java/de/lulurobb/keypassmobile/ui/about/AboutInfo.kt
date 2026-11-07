package de.lulurobb.keypassmobile.ui.about

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which build type the running app was built as. */
enum class BuildVariant {
    Release,
    Dev,
    Debug,
    ;

    companion object {
        /** BuildConfig.BUILD_TYPE; every name other than "release" and "dev" counts as debug. */
        fun of(buildType: String): BuildVariant = when (buildType) {
            "release" -> Release
            "dev" -> Dev
            else -> Debug
        }
    }
}

/**
 * Facts about the app and the device for the About screen and the bug report text.
 * Nothing secret in here. [commit] and [buildTime] are null for a local build (no CI values).
 */
data class AboutInfo(
    val versionName: String,
    val versionCode: Long,
    val variant: BuildVariant,
    val packageName: String,
    /** Full commit id as built by CI, null for a local build. */
    val commit: String?,
    /** When CI built the app, null for a local build. */
    val buildTime: Instant?,
    /** Android version name, e.g. "16". */
    val androidRelease: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
) {
    /** Short commit id (7 characters) for display; null for a local build. */
    val shortCommit: String? get() = commit?.take(SHORT_COMMIT_LENGTH)

    /** GitHub page of the commit; null for a local build. */
    val commitUrl: String? get() = commit?.let { "${AboutLinks.REPOSITORY}/commit/$it" }

    /** "Samsung SM-A556B"; see [deviceName]. */
    val device: String get() = deviceName(manufacturer, model)

    companion object {
        const val SHORT_COMMIT_LENGTH = 7

        /**
         * Reads the BuildConfig strings: empty means "not provided" (local build). A build time that
         * cannot be read counts as not provided, the Gradle script already rejects malformed values.
         */
        fun parseBuildValues(commit: String, buildTime: String): Pair<String?, Instant?> =
            commit.takeIf { it.isNotBlank() } to
                buildTime.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }
}

/** Where the project lives. The creator name is the GitHub name on purpose. */
object AboutLinks {
    const val CREATOR = "LuluRobb"
    const val REPOSITORY = "https://github.com/LuluRobb/Keypass-Mobile"
    const val RELEASES = "$REPOSITORY/releases"
    const val ISSUES = "$REPOSITORY/issues"

    /** Repository address without the scheme, as shown in the row. */
    const val REPOSITORY_DISPLAY = "github.com/LuluRobb/Keypass-Mobile"
}

private val BUILD_TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMANY)

/** "07.11.2026, 14:32" in [zone] (the device time zone in the app). */
fun formatBuildTime(time: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    BUILD_TIME_FORMAT.format(time.atZone(zone))

/**
 * Manufacturer and model for display. Many models already start with the manufacturer
 * ("Google Pixel 9"): it is not repeated then. A lower case manufacturer gets a capital letter.
 */
fun deviceName(manufacturer: String, model: String): String {
    val maker = manufacturer.trim()
    val name = model.trim()
    if (maker.isEmpty()) return name
    if (name.isEmpty()) return maker.capitalized()
    if (name.startsWith(maker, ignoreCase = true)) return name.capitalized()
    return "${maker.capitalized()} $name"
}

private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }

/**
 * Plain text for a bug report: [title] on the first line, then one "label: value" line each.
 * Same wording as the screen.
 */
fun aboutSummary(title: String, lines: List<Pair<String, String>>): String =
    (listOf(title) + lines.map { (label, value) -> "$label: $value" }).joinToString("\n")
