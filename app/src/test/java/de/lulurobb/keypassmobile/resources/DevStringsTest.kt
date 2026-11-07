package de.lulurobb.keypassmobile.resources

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * The dev build type (app/src/dev/res) overrides every string that shows the app name with
 * "KeyPass Dev". Those copies would go stale when a text changes in main. This test keeps
 * them in step: every main string with "KeyPass Mobile" needs an override, and an override
 * must equal its original except for the name.
 */
class DevStringsTest {

    private val mainName = "KeyPass Mobile"
    private val devName = "KeyPass Dev"

    // Gradle runs unit tests with the module directory (app) as working directory.
    private val root = File("..").canonicalFile

    /** Strings of all modules, key = "<values folder>/<name>". :app comes last and wins, like in the merge. */
    private val mainStrings: Map<String, Pair<String, File>> = buildMap {
        val modules = root.listFiles { f -> File(f, "src/main/res").isDirectory }.orEmpty()
            .sortedBy { it.name == "app" }
        for (module in modules) putAll(readStrings(File(module, "src/main/res")))
    }

    private val devStrings: Map<String, Pair<String, File>> = readStrings(File(root, "app/src/dev/res"))

    @Test
    fun everyMainStringWithTheAppNameHasADevOverride() {
        val missing = mainStrings.filter { (key, entry) ->
            entry.first.contains(mainName) && key !in devStrings
        }
        assertTrue(
            missing.isEmpty(),
            missing.entries.joinToString("\n", prefix = "Missing in app/src/dev/res (add them with \"$devName\"):\n") { (key, entry) ->
                "  $key from ${relative(entry.second)}: ${entry.first}"
            },
        )
    }

    @Test
    fun devOverridesOnlyDifferInTheAppName() {
        val problems = devStrings.mapNotNull { (key, dev) ->
            val main = mainStrings[key]
                ?: return@mapNotNull "  $key in ${relative(dev.second)} has no original in main (remove it)"
            val expected = main.first.replace(mainName, devName)
            if (dev.first == expected) null
            else "  $key in ${relative(dev.second)}:\n    expected: $expected\n    found:    ${dev.first}"
        }
        assertTrue(
            problems.isEmpty(),
            problems.joinToString("\n", prefix = "Dev strings differ from main in more than the app name (mirror the change from main):\n"),
        )
    }

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    /** All <string> elements of the strings.xml files in the values folders below [res]. */
    private fun readStrings(res: File): Map<String, Pair<String, File>> = buildMap {
        val folders = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
        for (folder in folders) {
            val file = File(folder, "strings.xml")
            if (!file.isFile) continue
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                put("${folder.name}/${element.getAttribute("name")}", element.textContent to file)
            }
        }
    }
}
