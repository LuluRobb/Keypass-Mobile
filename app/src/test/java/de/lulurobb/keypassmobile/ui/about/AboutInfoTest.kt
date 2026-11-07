package de.lulurobb.keypassmobile.ui.about

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AboutInfoTest {

    private val sha = "0123456789abcdef0123456789abcdef01234567"

    private fun info(commit: String? = sha, buildTime: Instant? = Instant.parse("2026-11-07T12:30:00Z")) = AboutInfo(
        versionName = "0.3.0.7",
        versionCode = 7,
        variant = BuildVariant.Release,
        packageName = "de.lulurobb.keypassmobile",
        commit = commit,
        buildTime = buildTime,
        androidRelease = "16",
        apiLevel = 36,
        manufacturer = "samsung",
        model = "SM-A556B",
    )

    @Test
    fun `the commit is shown with seven characters and links to its GitHub page`() {
        val info = info()
        assertEquals("0123456", info.shortCommit)
        assertEquals("https://github.com/LuluRobb/Keypass-Mobile/commit/$sha", info.commitUrl)
    }

    @Test
    fun `a local build has no commit and no link`() {
        val info = info(commit = null)
        assertNull(info.shortCommit)
        assertNull(info.commitUrl)
    }

    @Test
    fun `a commit shorter than seven characters is shown whole`() {
        assertEquals("abc12", info(commit = "abc12").shortCommit)
    }

    @Test
    fun `the project links point at the repository`() {
        assertEquals("https://github.com/LuluRobb/Keypass-Mobile/releases", AboutLinks.RELEASES)
        assertEquals("https://github.com/LuluRobb/Keypass-Mobile/issues", AboutLinks.ISSUES)
        assertEquals("https://" + AboutLinks.REPOSITORY_DISPLAY, AboutLinks.REPOSITORY)
        assertEquals("LuluRobb", AboutLinks.CREATOR)
    }

    @Test
    fun `build values of BuildConfig are empty for a local build`() {
        assertEquals(null to null, AboutInfo.parseBuildValues("", ""))
        assertEquals(null to null, AboutInfo.parseBuildValues("  ", " "))
    }

    @Test
    fun `build values of CI are read`() {
        val (commit, time) = AboutInfo.parseBuildValues(sha, "2026-11-07T12:30:00Z")
        assertEquals(sha, commit)
        assertEquals(Instant.parse("2026-11-07T12:30:00Z"), time)
    }

    @Test
    fun `an unreadable build time counts as missing`() {
        val (commit, time) = AboutInfo.parseBuildValues(sha, "gestern")
        assertEquals(sha, commit)
        assertNull(time)
    }

    @Test
    fun `the build type decides the variant`() {
        assertEquals(BuildVariant.Release, BuildVariant.of("release"))
        assertEquals(BuildVariant.Dev, BuildVariant.of("dev"))
        assertEquals(BuildVariant.Debug, BuildVariant.of("debug"))
        assertEquals(BuildVariant.Debug, BuildVariant.of("anything"))
    }

    @Test
    fun `the build time is shown in the given zone, German format`() {
        val time = Instant.parse("2026-11-07T23:30:00Z")
        assertEquals("08.11.2026, 00:30", formatBuildTime(time, ZoneId.of("Europe/Berlin")))
        assertEquals("07.11.2026, 23:30", formatBuildTime(time, ZoneId.of("UTC")))
        assertEquals("07.11.2026, 18:30", formatBuildTime(time, ZoneId.of("America/New_York")))
    }

    @Test
    fun `the manufacturer gets a capital letter`() {
        assertEquals("Samsung SM-A556B", deviceName("samsung", "SM-A556B"))
        assertEquals("Samsung SM-A556B", info().device)
    }

    @Test
    fun `a model that starts with the manufacturer is not doubled`() {
        assertEquals("Google Pixel 9", deviceName("Google", "Google Pixel 9"))
        assertEquals("Xiaomi 14", deviceName("xiaomi", "xiaomi 14"))
    }

    @Test
    fun `a missing manufacturer or model leaves the other one`() {
        assertEquals("Pixel 9", deviceName("", "Pixel 9"))
        assertEquals("Google", deviceName("google", ""))
        assertEquals("", deviceName("", ""))
    }

    @Test
    fun `the summary has the title and one line per fact`() {
        val text = aboutSummary(
            "KeyPass Mobile",
            listOf("Version" to "0.3.0.7", "Build-Nummer" to "7", "Commit" to "0123456"),
        )
        assertEquals("KeyPass Mobile\nVersion: 0.3.0.7\nBuild-Nummer: 7\nCommit: 0123456", text)
    }

    @Test
    fun `the summary of nothing is the title`() {
        assertEquals("KeyPass Dev", aboutSummary("KeyPass Dev", emptyList()))
    }
}
