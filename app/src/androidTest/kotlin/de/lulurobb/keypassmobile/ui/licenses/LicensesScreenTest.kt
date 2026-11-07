package de.lulurobb.keypassmobile.ui.licenses

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.state.AppRoute
import de.lulurobb.keypassmobile.ui.AboutStateKey
import de.lulurobb.keypassmobile.ui.LicensesStateKey
import de.lulurobb.keypassmobile.ui.about.AboutInfo
import de.lulurobb.keypassmobile.ui.about.AboutScreen
import de.lulurobb.keypassmobile.ui.about.BuildVariant
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.rememberScreenStateHolder
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The licenses list and the license page: texts, taps, the link row, the real generated data and the kept scroll positions. */
@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val apache = LicenseEntry(
        id = "androidx.activity:activity",
        name = "Activity",
        version = "1.13.0",
        licenseNames = listOf("Apache License 2.0"),
        licenseText = "Apache License\nVersion 2.0, January 2004\nTERMS AND CONDITIONS FOR USE",
        developers = listOf("The Android Open Source Project"),
        organization = null,
        website = "https://developer.android.com/jetpack/androidx/releases/activity#1.13.0",
        description = "Provides the base Activity subclass.",
    )

    private val mit = LicenseEntry(
        id = "net.engio:mbassador",
        name = "mbassador",
        version = "1.3.2",
        licenseNames = listOf("MIT License"),
        licenseText = "MIT License\n\nCopyright (c) 2012 Benjamin Diedrichsen",
        developers = listOf("Benjamin Diedrichsen"),
        organization = "Example Org",
        website = "http://example.org/mbassador/",
        description = null,
    )

    private val manual = LicenseEntry(
        id = "publicsuffix.org:public-suffix-list",
        name = "Public Suffix List",
        version = null,
        licenseNames = listOf("Mozilla Public License 2.0"),
        licenseText = "",
        developers = emptyList(),
        organization = null,
        website = null,
        description = null,
    )

    private val opened = mutableListOf<String>()
    private val links = mutableListOf<String>()
    private var back = 0

    private fun list(state: LicenseListState) {
        rule.setContent {
            KeyPassTheme { LicensesScreen(state = state, onBack = { back++ }, onOpen = { opened += it.id }) }
        }
    }

    private fun detail(entry: LicenseEntry?, message: UiMessage? = null) {
        rule.setContent {
            KeyPassTheme {
                LicenseDetailScreen(entry = entry, onBack = { back++ }, onOpenLink = { links += it }, message = message)
            }
        }
    }

    @Test
    fun listShowsNameVersionAndLicenseOfEveryEntry() {
        list(LicenseListState.Loaded(listOf(apache, mit, manual)))
        rule.onNodeWithText("Open-Source-Lizenzen").assertIsDisplayed()
        rule.onNode(hasText("Activity") and hasText("1.13.0, Apache License 2.0")).assertIsDisplayed()
        rule.onNode(hasText("mbassador") and hasText("1.3.2, MIT License")).assertIsDisplayed()
        // No version: the license alone.
        rule.onNode(hasText("Public Suffix List") and hasText("Mozilla Public License 2.0")).assertIsDisplayed()
    }

    @Test
    fun tappingAnEntryOpensItAndBackGoesBack() {
        list(LicenseListState.Loaded(listOf(apache, mit)))
        rule.onNode(hasText("mbassador") and hasClickAction()).assertHasClickAction().performClick()
        assertEquals(listOf("net.engio:mbassador"), opened)
        rule.onNodeWithContentDescription("Zurück").performClick()
        assertEquals(1, back)
    }

    @Test
    fun loadingShowsAnAnnouncedSpinnerAndNoFailureText() {
        list(LicenseListState.Loading)
        rule.onNodeWithText("Open-Source-Lizenzen").assertIsDisplayed()
        rule.onNodeWithContentDescription("Lizenzen werden geladen").assertIsDisplayed()
        rule.onAllNodesWithText("Die Lizenzliste ließ sich nicht laden.").assertCountEquals(0)
    }

    @Test
    fun aFailedLoadShowsAMessage() {
        list(LicenseListState.Failed)
        rule.onNodeWithText("Die Lizenzliste ließ sich nicht laden.").assertIsDisplayed()
    }

    @Test
    fun detailShowsTheFactsAndTheLicenseText() {
        detail(apache)
        rule.onNodeWithText("Activity").assertIsDisplayed()
        rule.onNodeWithText("Provides the base Activity subclass.").assertIsDisplayed()
        rule.onNode(hasText("Version") and hasText("1.13.0")).assertIsDisplayed()
        rule.onNode(hasText("Lizenz") and hasText("Apache License 2.0")).assertIsDisplayed()
        rule.onNode(hasText("Entwickler") and hasText("The Android Open Source Project")).assertIsDisplayed()
        rule.onNodeWithText("Lizenztext").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Apache License\nVersion 2.0, January 2004\nTERMS AND CONDITIONS FOR USE").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun detailShowsAnOrganizationThatAddsToTheDevelopers() {
        detail(mit)
        rule.onNode(hasText("Organisation") and hasText("Example Org")).assertIsDisplayed()
        rule.onNode(hasText("Entwickler") and hasText("Benjamin Diedrichsen")).assertIsDisplayed()
    }

    @Test
    fun websiteRowOpensTheBrowserAndIsAnnouncedAsSuch() {
        detail(apache)
        val row = rule.onNode(hasText("Website") and hasText("developer.android.com/jetpack/androidx/releases/activity#1.13.0"))
        row.assertHasClickAction()
        assertEquals("Im Browser öffnen", row.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        row.performClick()
        assertEquals(listOf("https://developer.android.com/jetpack/androidx/releases/activity#1.13.0"), links)
    }

    @Test
    fun readOnlyFactsCannotBeClicked() {
        detail(apache)
        rule.onNode(hasText("Version") and hasText("1.13.0")).assertHasNoClickAction()
        rule.onNode(hasText("Lizenz") and hasText("Apache License 2.0")).assertHasNoClickAction()
    }

    @Test
    fun anEntryWithoutWebsiteVersionAndTextHasNoLinkAndSaysSo() {
        detail(manual)
        rule.onAllNodesWithText("Website").assertCountEquals(0)
        rule.onAllNodesWithText("Version").assertCountEquals(0)
        rule.onNodeWithText("Für diesen Eintrag ist kein Lizenztext hinterlegt. Die Lizenz steht auf der Website.").assertIsDisplayed()
    }

    @Test
    fun detailWithoutEntryShowsTheTopBarAndTheFailureLine() {
        detail(null)
        rule.onNodeWithText("Open-Source-Lizenzen").assertIsDisplayed()
        rule.onNodeWithText("Die Lizenzliste ließ sich nicht laden.").assertIsDisplayed()
        rule.onAllNodesWithText("Lizenztext").assertCountEquals(0)
        rule.onNodeWithContentDescription("Zurück").performClick()
        assertEquals(1, back)
    }

    @Test
    fun noBrowserMessageShowsOnTheDetailPage() {
        detail(apache, UiMessage.NoBrowser)
        rule.onNodeWithText("Kein Browser gefunden.").assertIsDisplayed()
    }

    // ---- the real data of this build (debug variant of the generated JSON) ----

    @Test
    fun theGeneratedDataHasTextForEveryEntryAndTheManualEntries() {
        val entries = loadLicenseEntries(ApplicationProvider.getApplicationContext())
        assertTrue("only ${entries.size} entries", entries.size > 50)
        val withoutText = entries.filter { it.licenseText.isBlank() }.map { it.id }
        assertTrue("entries without license text: $withoutText", withoutText.isEmpty())
        val withoutLicense = entries.filter { it.licenseNames.isEmpty() }.map { it.id }
        assertTrue("entries without license name: $withoutLicense", withoutLicense.isEmpty())
        assertEquals(entries.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER), entries.map { it.name })
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        for (id in listOf(
            "publicsuffix.org:public-suffix-list",
            "com.google.fonts:material-symbols",
            "org.p-h-c:argon2-reference",
            // Code embedded in kotpass.
            "org.redundent:kotlin-xml-builder",
            "org.apache.commons:commons-lang3",
            // Libraries of the library modules, not only of :app.
            "com.hierynomus:smbj",
            "app.keemobile:kotpass",
            "com.lambdapioneer.argon2kt:argon2kt",
            "androidx.work:work-runtime",
        )) {
            assertNotNull("missing $id", entries.firstOrNull { it.id == id })
        }
        // No BOM (they ship no code) and none of the test tooling.
        assertTrue(entries.none { it.id.endsWith("-bom") || it.id == "junit:junit" })
        // Own MIT texts with the copyright line of the project.
        assertTrue(entries.first { it.id == "app.keemobile:kotpass" }.licenseText.contains("Copyright (c) 2021 Denis T."))
        assertTrue(entries.first { it.id == "org.bouncycastle:bcprov-jdk18on" }.licenseText.contains("Legion of the Bouncy Castle"))
        assertFalse(entries.any { it.licenseText.contains("<year>") })
    }

    // ---- scroll positions kept while a screen opened from them is on top ----

    private var route by mutableStateOf<AppRoute>(AppRoute.Licenses)

    private val many = (0 until 80).map { i ->
        apache.copy(id = "lib:$i", name = "Library %02d".format(i), version = "1.$i")
    }

    @Test
    fun theListKeepsItsScrollPositionAcrossADetailPage() {
        rule.setContent {
            KeyPassTheme {
                val holder = rememberScreenStateHolder(route)
                if (route == AppRoute.Licenses) {
                    holder.SaveableStateProvider(LicensesStateKey) {
                        LicensesScreen(
                            state = LicenseListState.Loaded(many),
                            onBack = {},
                            onOpen = { route = AppRoute.LicenseDetail(it.id) },
                            // A short window, so the list always has to scroll.
                            modifier = Modifier.height(500.dp),
                        )
                    }
                }
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Library 60"))
        rule.onNodeWithText("Library 00").assertDoesNotExist()

        rule.onNodeWithText("Library 60").performClick()
        rule.waitForIdle()
        assertEquals(AppRoute.LicenseDetail("lib:60"), route)
        route = AppRoute.Licenses
        rule.waitForIdle()

        rule.onNodeWithText("Library 60").assertIsDisplayed()
        rule.onNodeWithText("Library 00").assertDoesNotExist()
    }

    @Test
    fun theListStartsAtTheTopAgainAfterLeavingTheLicensesPages() {
        rule.setContent {
            KeyPassTheme {
                val holder = rememberScreenStateHolder(route)
                if (route == AppRoute.Licenses) {
                    holder.SaveableStateProvider(LicensesStateKey) {
                        LicensesScreen(
                            state = LicenseListState.Loaded(many),
                            onBack = {},
                            onOpen = {},
                            modifier = Modifier.height(500.dp),
                        )
                    }
                }
            }
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Library 60"))

        route = AppRoute.About
        rule.waitForIdle()
        route = AppRoute.Licenses
        rule.waitForIdle()

        rule.onNodeWithText("Library 00").assertIsDisplayed()
        rule.onNodeWithText("Library 60").assertDoesNotExist()
    }

    @Test
    fun aboutKeepsItsScrollPositionAcrossTheLicensesList() {
        val info = AboutInfo(
            versionName = "0.4.0",
            versionCode = 7,
            variant = BuildVariant.Release,
            packageName = "de.lulurobb.keypassmobile",
            commit = null,
            buildTime = null,
            androidRelease = "16",
            apiLevel = 36,
            manufacturer = "samsung",
            model = "SM-A556B",
        )
        route = AppRoute.About
        rule.setContent {
            KeyPassTheme {
                val holder = rememberScreenStateHolder(route)
                if (route == AppRoute.About) {
                    holder.SaveableStateProvider(AboutStateKey) {
                        AboutScreen(
                            info = info,
                            onBack = {},
                            onOpenLink = {},
                            onOpenLicenses = { route = AppRoute.Licenses },
                            onCopyInfo = {},
                            modifier = Modifier.height(500.dp),
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Open-Source-Lizenzen").performScrollTo()
        rule.onNodeWithText("Version").assertIsNotDisplayed()

        rule.onNodeWithText("Open-Source-Lizenzen").performClick()
        rule.waitForIdle()
        assertEquals(AppRoute.Licenses, route)
        route = AppRoute.About
        rule.waitForIdle()

        rule.onNodeWithText("Open-Source-Lizenzen").assertIsDisplayed()
        rule.onNodeWithText("Version").assertIsNotDisplayed()
    }
}
