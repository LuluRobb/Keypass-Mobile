package de.lulurobb.keypassmobile.ui.licenses

import android.content.Context
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import de.lulurobb.keypassmobile.R

/**
 * One row of the open-source licenses list and the content of its detail page: a Maven library from the
 * generated data or a manual entry (app/licenses-config/libraries). [id] is the unique id of the
 * library and stays stable for navigation.
 */
data class LicenseEntry(
    val id: String,
    val name: String,
    /** Null for entries without a version (the manual ones may have none). */
    val version: String?,
    /** Names of the licenses that have a text, e.g. "Apache License 2.0". Empty when nothing is known. */
    val licenseNames: List<String>,
    /** Full license text(s); empty when the data has none. */
    val licenseText: String,
    val developers: List<String>,
    /** Organization when it adds something to [developers]. */
    val organization: String?,
    /** Web address (http or https only), null when unknown. */
    val website: String?,
    /** Short description of the library, one line. */
    val description: String?,
) {
    /** "Apache License 2.0" or "A, B" for the list row and the detail page; empty without license. */
    val licenseLabel: String get() = licenseNames.joinToString(", ")

    /** Second line of the list row: version and license, whatever is known. */
    val summary: String get() = listOfNotNull(version, licenseLabel.takeIf { it.isNotEmpty() }).joinToString(", ")
}

/**
 * The generated JSON of the AboutLibraries plugin (res/raw/aboutlibraries.json) as sorted entries.
 * The parser of the library logs a broken file and returns an empty list instead of throwing, so an
 * empty result is turned into an exception here (the list then shows "nicht laden").
 */
fun loadLicenseEntries(context: Context): List<LicenseEntry> {
    val entries = context.resources.openRawResource(R.raw.aboutlibraries).use { parseLicenses(it.readBytes().decodeToString()) }
    check(entries.isNotEmpty()) { "aboutlibraries.json has no libraries" }
    return entries
}

/** Parses the plugin JSON; see [toLicenseEntries]. */
fun parseLicenses(json: String): List<LicenseEntry> = toLicenseEntries(Libs.Builder().withJson(json).build())

/**
 * All libraries of [libs] as entries, sorted by name (case-insensitive, then by id). Licenses without
 * text are left out as long as the library has another one with text: a custom license of
 * licenses-config is merged with the plain license id the library came with, which has no text offline.
 */
fun toLicenseEntries(libs: Libs): List<LicenseEntry> =
    libs.libraries.map(::toEntry).sortedWith(compareBy<LicenseEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })

private fun toEntry(library: Library): LicenseEntry {
    val licenses = library.licenses.toList()
    val withText = licenses.filter { !it.licenseContent.isNullOrBlank() }
    val shown = withText.ifEmpty { licenses }
    val developers = library.developers.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    val organization = library.organization?.name?.trim()?.takeIf { it.isNotEmpty() && it !in developers }
    return LicenseEntry(
        id = library.uniqueId,
        name = library.name.trim().takeIf { it.isNotEmpty() } ?: library.uniqueId,
        version = library.artifactVersion?.trim()?.takeIf { it.isNotEmpty() },
        licenseNames = shown.mapNotNull(::licenseName).distinct(),
        licenseText = joinTexts(withText),
        developers = developers,
        organization = organization,
        website = (library.website ?: library.scm?.url).webAddress(),
        description = library.description?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() },
    )
}

private fun licenseName(license: License): String? =
    listOf(license.name, license.spdxId, license.hash).firstOrNull { !it.isNullOrBlank() }?.trim()

/** One text as it is, several with their name above each. Line breaks are normalized to LF. */
private fun joinTexts(licenses: List<License>): String {
    val texts = licenses.map { (licenseName(it) ?: "") to it.licenseContent.orEmpty().replace("\r\n", "\n").trim() }
    return if (texts.size == 1) texts.single().second else texts.joinToString("\n\n") { (name, text) -> "$name\n\n$text" }
}

private fun String?.webAddress(): String? =
    this?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

private val WHITESPACE = Regex("\\s+")
