package de.lulurobb.keypassmobile.ui.licenses

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mapping of the plugin JSON to the entries of the licenses pages, and the manual entries of licenses-config. */
class LicenseEntryTest {

    private fun library(
        id: String,
        name: String = id,
        version: String? = "1.0",
        licenses: List<String> = listOf("Apache-2.0"),
        extra: String = "",
    ): String {
        val versionPart = if (version != null) """"artifactVersion":"$version",""" else ""
        val licensesPart = licenses.joinToString(",") { "\"$it\"" }
        return """{"uniqueId":"$id",$versionPart"name":"$name",$extra"licenses":[$licensesPart]}"""
    }

    private fun json(libraries: List<String>, licenses: String = APACHE) =
        """{"libraries":[${libraries.joinToString(",")}],"licenses":{$licenses}}"""

    @Test
    fun `entries are sorted by name without regard to case, then by id`() {
        val entries = parseLicenses(
            json(
                listOf(
                    library("b:one", name = "okio"),
                    library("a:two", name = "Compose UI"),
                    library("c:three", name = "annotation"),
                    library("z:same", name = "Core"),
                    library("a:same", name = "core"),
                ),
            ),
        )
        assertEquals(listOf("c:three", "a:two", "a:same", "z:same", "b:one"), entries.map { it.id })
        assertEquals(listOf("annotation", "Compose UI", "core", "Core", "okio"), entries.map { it.name })
    }

    @Test
    fun `a library shows its version, license name and text`() {
        val entry = parseLicenses(json(listOf(library("a:b", name = "Alpha", version = "2.5.1")))).single()
        assertEquals("a:b", entry.id)
        assertEquals("Alpha", entry.name)
        assertEquals("2.5.1", entry.version)
        assertEquals(listOf("Apache License 2.0"), entry.licenseNames)
        assertEquals("Apache License 2.0", entry.licenseLabel)
        assertEquals("2.5.1, Apache License 2.0", entry.summary)
        assertEquals("Apache License\nVersion 2.0, January 2004", entry.licenseText)
    }

    @Test
    fun `a license without text is left out while the library has another one with text`() {
        // A custom license of licenses-config comes next to the plain id the library was found with.
        val entry = parseLicenses(
            json(
                listOf(library("app.keemobile:kotpass", licenses = listOf("mit-kotpass", "MIT"))),
                licenses = """$MIT_KOTPASS,"MIT":{"name":"MIT","hash":"MIT"}""",
            ),
        ).single()
        assertEquals(listOf("MIT License"), entry.licenseNames)
        assertTrue(entry.licenseText.startsWith("MIT License\n\nCopyright (c) 2021 Denis T."), entry.licenseText)
    }

    @Test
    fun `a library with no license text at all keeps the license name and has an empty text`() {
        val entry = parseLicenses(
            json(
                listOf(library("x:y", licenses = listOf("Custom"))),
                licenses = """"Custom":{"name":"Custom License","hash":"Custom"}""",
            ),
        ).single()
        assertEquals(listOf("Custom License"), entry.licenseNames)
        assertEquals("", entry.licenseText)
        assertEquals("1.0, Custom License", entry.summary)
    }

    @Test
    fun `a library without any license has only the version in its summary`() {
        val entry = parseLicenses(json(listOf(library("x:y", licenses = emptyList())))).single()
        assertEquals(emptyList(), entry.licenseNames)
        assertEquals("1.0", entry.summary)
    }

    @Test
    fun `an entry without a version leaves it out`() {
        val entry = parseLicenses(json(listOf(library("x:y", version = null)))).single()
        assertNull(entry.version)
        assertEquals("Apache License 2.0", entry.summary)
    }

    @Test
    fun `several licenses with text are joined, each under its name`() {
        val entry = parseLicenses(
            json(
                listOf(library("x:y", licenses = listOf("Apache-2.0", "mit-kotpass"))),
                licenses = "$APACHE,$MIT_KOTPASS",
            ),
        ).single()
        assertEquals(listOf("Apache License 2.0", "MIT License"), entry.licenseNames)
        assertTrue(entry.licenseText.startsWith("Apache License 2.0\n\nApache License\n"), entry.licenseText)
        assertTrue(entry.licenseText.contains("\n\nMIT License\n\nMIT License\n\nCopyright (c) 2021"), entry.licenseText)
    }

    @Test
    fun `the organization shows only when it adds to the developers`() {
        val same = """"developers":[{"name":"The Android Open Source Project"}],"organization":{"name":"The Android Open Source Project"},"""
        val other = """"developers":[{"name":"Ceki Gulcu"},{"name":" Ceki Gulcu "}],"organization":{"name":"QOS.ch"},"""
        val entries = parseLicenses(json(listOf(library("a:same", extra = same), library("b:other", extra = other))))
        val sameEntry = entries.first { it.id == "a:same" }
        assertEquals(listOf("The Android Open Source Project"), sameEntry.developers)
        assertNull(sameEntry.organization)
        val otherEntry = entries.first { it.id == "b:other" }
        assertEquals(listOf("Ceki Gulcu"), otherEntry.developers)
        assertEquals("QOS.ch", otherEntry.organization)
    }

    @Test
    fun `the website falls back to the repository and only web addresses are kept`() {
        val entries = parseLicenses(
            json(
                listOf(
                    library("a:site", extra = """"website":"https://example.org/a","scm":{"url":"https://github.com/x/a"},"""),
                    library("b:scm", extra = """"scm":{"url":"https://github.com/x/b"},"""),
                    library("c:http", extra = """"website":"http://www.slf4j.org","""),
                    library("d:odd", extra = """"website":"javascript:alert(1)","""),
                    library("e:none"),
                ),
            ),
        ).associateBy { it.id }
        assertEquals("https://example.org/a", entries.getValue("a:site").website)
        assertEquals("https://github.com/x/b", entries.getValue("b:scm").website)
        assertEquals("http://www.slf4j.org", entries.getValue("c:http").website)
        assertNull(entries.getValue("d:odd").website)
        assertNull(entries.getValue("e:none").website)
    }

    @Test
    fun `the description is one line and blank values count as missing`() {
        val entries = parseLicenses(
            json(
                listOf(
                    library("a:text", extra = """"description":"Fast and\n        flexible   event bus.","""),
                    library("b:blank", extra = """"description":"   ","""),
                    library("c:noname", name = " "),
                ),
            ),
        ).associateBy { it.id }
        assertEquals("Fast and flexible event bus.", entries.getValue("a:text").description)
        assertNull(entries.getValue("b:blank").description)
        // A library without a name shows its unique id.
        assertEquals("c:noname", entries.getValue("c:noname").name)
    }

    @Test
    fun `line breaks of a license text are normalized`() {
        val crlf = """"CRLF":{"name":"Windows","hash":"CRLF","content":"one\r\ntwo\r\n"}"""
        val entry = parseLicenses(json(listOf(library("x:y", licenses = listOf("CRLF"))), licenses = crlf)).single()
        assertEquals("one\ntwo", entry.licenseText)
    }

    // ---- the manual entries and own license texts of app/licenses-config ----

    // Gradle runs unit tests with the module directory (app) as working directory.
    private val config = File("licenses-config")

    /** The plugin JSON as the plugin merges it: all manual libraries (those with a name) and all license files. */
    private fun configJson(): String {
        val libraries = File(config, "libraries").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            .map { it.readText() }.filter { it.contains("\"name\"") }
        val licenses = File(config, "licenses").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            .joinToString(",") { "\"${it.nameWithoutExtension}\":${it.readText()}" }
        return json(libraries, licenses)
    }

    @Test
    fun `the Public Suffix List is a manual entry with its MPL notice and source`() {
        val entry = parseLicenses(configJson()).first { it.id == "publicsuffix.org:public-suffix-list" }
        assertEquals("Public Suffix List", entry.name)
        assertEquals(listOf("Mozilla Public License 2.0"), entry.licenseNames)
        assertTrue(entry.licenseText.startsWith("Mozilla Public License Version 2.0"), entry.licenseText.take(60))
        assertEquals("https://publicsuffix.org/", entry.website)
        // MPL 2.0, 3.2: tell where the source of the data can be obtained.
        assertTrue(entry.description.orEmpty().contains("https://publicsuffix.org/list/public_suffix_list.dat"))
        assertNotNull(entry.version)
    }

    @Test
    fun `the Material Symbols and the Argon2 reference are manual entries under Apache 2_0`() {
        val entries = parseLicenses(configJson()).associateBy { it.id }
        for (id in listOf("com.google.fonts:material-symbols", "org.p-h-c:argon2-reference")) {
            val entry = entries.getValue(id)
            assertEquals(listOf("Apache License 2.0"), entry.licenseNames, id)
            assertTrue(entry.licenseText.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"), id)
            assertNotNull(entry.website, id)
            assertTrue(entry.developers.isNotEmpty(), id)
        }
        assertEquals(listOf("Google LLC"), entries.getValue("com.google.fonts:material-symbols").developers)
    }

    @Test
    fun `every manual entry has a license text and the own MIT texts carry a copyright line`() {
        val licenseFiles = File(config, "licenses").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(licenseFiles.size >= 7, "licenses in ${config.absolutePath}")
        val entries = parseLicenses(configJson())
        assertTrue(entries.size >= 3)
        assertTrue(entries.all { it.licenseText.isNotBlank() }, "a manual entry without text")
        for (name in listOf("mit-kotpass", "mit-mbassador", "mit-argon2kt", "mit-slf4j", "bouncy-castle")) {
            val text = File(config, "licenses/$name.json").readText()
            assertTrue(text.contains("Copyright (c)"), "$name has no copyright line")
        }
    }

    @Test
    fun `the own license texts carry the exact copyright lines of the upstream projects`() {
        val licenseTexts = File(config, "licenses").listFiles { f -> f.extension == "json" }.orEmpty()
            .associate { it.nameWithoutExtension to it.readText() }
        val expected = mapOf(
            "mit-kotpass" to "Copyright (c) 2021 Denis T.",
            "mit-mbassador" to "Copyright (c) 2012 Benjamin Diedrichsen",
            "mit-argon2kt" to "Copyright (c) Daniel Hugenroth",
            "mit-slf4j" to "Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)",
            "bouncy-castle" to "Copyright (c) 2000 - 2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)",
        )
        // Through the plugin JSON as the app reads it: each line must stand alone (whole line, not a part of one).
        val entries = parseLicenses(
            json(
                expected.keys.map { library("lib:$it", licenses = listOf(it)) },
                licenses = expected.keys.joinToString(",") { "\"$it\":${licenseTexts.getValue(it)}" },
            ),
        ).associateBy { it.id }
        for ((name, line) in expected) assertTrue(line in entries.getValue("lib:$name").licenseText.lines(), name)
    }

    @Test
    fun `the code embedded in kotpass is listed with its own license and notice`() {
        val entries = parseLicenses(configJson()).associateBy { it.id }
        val xml = entries.getValue("org.redundent:kotlin-xml-builder")
        assertEquals("Kotlin XML Builder", xml.name)
        assertEquals("1.9.3", xml.version)
        assertEquals(listOf("Apache License 2.0"), xml.licenseNames)
        assertEquals("https://github.com/redundent/kotlin-xml-builder", xml.website)
        assertTrue(xml.description.orEmpty().contains("kotpass"))
        assertTrue(xml.licenseText.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))

        val lang = entries.getValue("org.apache.commons:commons-lang3")
        assertEquals("Apache Commons Lang", lang.name)
        assertEquals("3.17.0", lang.version)
        assertEquals(listOf("Apache License 2.0"), lang.licenseNames)
        assertEquals("https://commons.apache.org/proper/commons-lang/", lang.website)
        assertTrue(lang.description.orEmpty().contains("kotpass"))
        // Apache 2.0, 4(d): the NOTICE of the embedded version (the NOTICE.txt that kotpass 0.13.0 ships), verbatim.
        val notice = listOf(
            "Apache Commons Lang",
            "Copyright 2001-2024 The Apache Software Foundation",
            "",
            "This product includes software developed at",
            "The Apache Software Foundation (https://www.apache.org/).",
        ).joinToString("\n")
        assertTrue(lang.licenseText.startsWith(notice + "\n\n"), lang.licenseText.take(200))
        assertTrue(lang.licenseText.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
    }

    @Test
    fun `the Argon2 entry names the copyright holders and the CC0 parts`() {
        val entry = parseLicenses(configJson()).first { it.id == "org.p-h-c:argon2-reference" }
        val description = entry.description.orEmpty()
        assertTrue(description.contains("Copyright 2015 Daniel Dinu, Dmitry Khovratovich, Jean-Philippe Aumasson und Samuel Neves"), description)
        assertTrue(description.contains("encoding.c, Thomas Pornin"), description)
        assertTrue(description.contains("BLAKE2 (Samuel Neves)"), description)
        assertTrue(description.contains("CC0 1.0"), description)
        assertTrue("Thomas Pornin" in entry.developers)
    }

    @Test
    fun `every library entry of the config points to a license file of the config`() {
        val ids = File(config, "licenses").listFiles().orEmpty().map { it.nameWithoutExtension }.toSet()
        val libraryFiles = File(config, "libraries").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(libraryFiles.isNotEmpty())
        for (file in libraryFiles) {
            val used = Regex("\"licenses\"\\s*:\\s*\\[([^\\]]*)]").find(file.readText())?.groupValues?.get(1).orEmpty()
            for (id in Regex("\"([^\"]+)\"").findAll(used).map { it.groupValues[1] }) {
                assertTrue(id in ids, "${file.name} uses license $id, which is not in licenses-config/licenses")
            }
        }
    }

    private companion object {
        const val APACHE = """"Apache-2.0":{"name":"Apache License 2.0","hash":"Apache-2.0","content":"Apache License\nVersion 2.0, January 2004"}"""
        const val MIT_KOTPASS = """"mit-kotpass":{"name":"MIT License","hash":"mit-kotpass","content":"MIT License\n\nCopyright (c) 2021 Denis T.\n\nPermission is hereby granted"}"""
    }
}
