package de.lulurobb.keypassmobile.ui.about

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** The About screen ("Über KeyPass Mobile"): rows, links, local build, copy button and its clipboard clip. */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val sha = "9f3c2a1b7d4e5f60718293a4b5c6d7e8f9012345"
    private val built = Instant.parse("2026-11-07T12:30:00Z")

    private val release = AboutInfo(
        versionName = "0.3.0",
        versionCode = 7,
        variant = BuildVariant.Release,
        packageName = "de.lulurobb.keypassmobile",
        commit = sha,
        buildTime = built,
        androidRelease = "16",
        apiLevel = 36,
        manufacturer = "samsung",
        model = "SM-A556B",
    )

    private val local = release.copy(
        versionName = "0.3.0-dev",
        versionCode = 1,
        variant = BuildVariant.Debug,
        commit = null,
        buildTime = null,
    )

    private val links = mutableListOf<String>()
    private val copies = mutableListOf<String>()

    private fun about(info: AboutInfo, message: UiMessage? = null) {
        rule.setContent {
            KeyPassTheme {
                AboutScreen(
                    info = info,
                    onBack = {},
                    onOpenLink = { links += it },
                    onCopyInfo = { copies += it },
                    message = message,
                )
            }
        }
    }

    private fun show(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun releaseBuildShowsGroupsAndFacts() {
        about(release)
        rule.onNodeWithText("Über KeyPass Mobile").assertIsDisplayed()
        for (heading in listOf("App", "Projekt", "Gerät")) show(heading)
        // Label and value sit in one node: TalkBack reads them together.
        for ((label, value) in listOf(
            "Version" to "0.3.0",
            "Build-Nummer" to "7",
            "Variante" to "Release",
            "Paketname" to "de.lulurobb.keypassmobile",
            "Commit" to "9f3c2a1",
            "Build-Datum" to formatBuildTime(built),
            "Ersteller" to "LuluRobb",
            "Android-Version" to "Android 16 (API 36)",
            "Modell" to "Samsung SM-A556B",
        )) {
            rule.onNode(hasText(label) and hasText(value)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun readOnlyRowsCannotBeClicked() {
        about(release)
        rule.onNode(hasText("Version") and hasText("0.3.0")).assertHasNoClickAction()
        rule.onNode(hasText("Ersteller")).performScrollTo().assertHasNoClickAction()
        rule.onNode(hasText("Modell")).performScrollTo().assertHasNoClickAction()
    }

    @Test
    fun commitRowOpensTheCommitOnGitHub() {
        about(release)
        rule.onNode(hasText("Commit") and hasText("9f3c2a1")).performScrollTo().assertHasClickAction().performClick()
        assertEquals(listOf("https://github.com/LuluRobb/Keypass-Mobile/commit/$sha"), links)
    }

    @Test
    fun projectRowsOpenTheirPages() {
        about(release)
        rule.onNode(hasText("Quellcode") and hasText("github.com/LuluRobb/Keypass-Mobile")).performScrollTo().performClick()
        rule.onNode(hasText("Releases")).performScrollTo().performClick()
        rule.onNode(hasText("Problem melden")).performScrollTo().performClick()
        assertEquals(
            listOf(
                "https://github.com/LuluRobb/Keypass-Mobile",
                "https://github.com/LuluRobb/Keypass-Mobile/releases",
                "https://github.com/LuluRobb/Keypass-Mobile/issues",
            ),
            links,
        )
    }

    @Test
    fun linkRowsAreAnnouncedAsOpeningInTheBrowser() {
        about(release)
        for (title in listOf("Quellcode", "Releases", "Problem melden", "Commit")) {
            val node = rule.onNode(hasText(title) and hasClickAction()).performScrollTo().fetchSemanticsNode()
            assertEquals(title, "Im Browser öffnen", node.config[SemanticsActions.OnClick].label)
        }
    }

    @Test
    fun localBuildSaysSoAndHasNoCommitLink() {
        about(local)
        rule.onNode(hasText("Commit") and hasText("Lokaler Build")).performScrollTo().assertHasNoClickAction()
        rule.onNode(hasText("Build-Datum") and hasText("Lokaler Build")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Variante") and hasText("Debug")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Version") and hasText("0.3.0-dev")).assertIsDisplayed()
        rule.onNode(hasText("Build-Nummer") and hasText("1")).assertIsDisplayed()
        rule.onAllNodesWithText("Lokaler Build", substring = true).assertCountEquals(2)
    }

    @Test
    fun copyButtonHandsOverTheSummary() {
        about(release)
        rule.onNodeWithText("Infos kopieren").performScrollTo().performClick()
        assertEquals(
            listOf(
                listOf(
                    "KeyPass Mobile",
                    "Version: 0.3.0",
                    "Build-Nummer: 7",
                    "Variante: Release",
                    "Paketname: de.lulurobb.keypassmobile",
                    "Commit: 9f3c2a1",
                    "Build-Datum: ${formatBuildTime(built)}",
                    "Android-Version: Android 16 (API 36)",
                    "Modell: Samsung SM-A556B",
                ).joinToString("\n"),
            ),
            copies,
        )
    }

    @Test
    fun summaryOfALocalBuildSaysLokalerBuild() {
        about(local)
        rule.onNodeWithText("Infos kopieren").performScrollTo().performClick()
        val text = copies.single()
        assertTrue(text, text.contains("Commit: Lokaler Build\n"))
        assertTrue(text, text.contains("Build-Datum: Lokaler Build\n"))
        assertTrue(text, text.contains("Variante: Debug\n"))
    }

    @Test
    fun messagesShowAsSnackbar() {
        about(release, UiMessage.InfoCopied)
        rule.onNodeWithText("Infos kopiert.").assertIsDisplayed()
    }

    @Test
    fun noBrowserMessageShowsAsSnackbar() {
        about(release, UiMessage.NoBrowser)
        rule.onNodeWithText("Kein Browser gefunden.").assertIsDisplayed()
    }

    @Test
    fun routePutsAPlainClipOnTheClipboardAndConfirms() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val messages = AppMessages()
        var back = 0
        rule.setContent {
            KeyPassTheme {
                AboutRoute(info = release, messages = messages, onBack = { back++ }, onOpenLink = { links += it })
            }
        }
        rule.onNodeWithText("Infos kopieren").performScrollTo().performClick()
        rule.onNodeWithText("Infos kopiert.").assertIsDisplayed()

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        assertNotNull(clip)
        val text = clip!!.getItemAt(0).text.toString()
        assertTrue(text, text.startsWith("KeyPass Mobile\nVersion: 0.3.0\n"))
        assertTrue(text, text.contains("Commit: 9f3c2a1\n"))
        // Nothing secret in it: no sensitive flag, unlike a copied password.
        val extras = clip.description.extras
        assertTrue(extras == null || !extras.containsKey(ClipDescription.EXTRA_IS_SENSITIVE))
        assertEquals(0, back)
    }
}
