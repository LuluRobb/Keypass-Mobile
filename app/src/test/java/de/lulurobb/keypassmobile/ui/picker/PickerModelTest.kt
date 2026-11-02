package de.lulurobb.keypassmobile.ui.picker

import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.state.FakeAppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.testEntry
import de.lulurobb.keypassmobile.state.testVault
import de.lulurobb.keypassmobile.state.unlockedView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PickerModelTest {

    private val paypal = testEntry("PayPal", username = "anna", url = "https://www.paypal.com/login", group = listOf("Finanzen"))
    private val bank = testEntry("Bank", url = "https://www.bank.example", group = listOf("Finanzen"))
    private val notes = testEntry("Zugangsdaten", group = listOf("Einkauf"))

    private val web = FillTarget.Web("login.paypal.com", "com.brave.browser")
    private val fakePage = FillTarget.Web("login.paypal.com.evil.example", "com.brave.browser")
    private val app = FillTarget.App("com.paypal.android", "PayPal")

    private val platform = FakeAppPlatform(session = unlockedView(testVault(listOf(paypal, bank, notes))))
    private val filled = mutableListOf<VaultEntry>()

    private fun TestScope.model(target: FillTarget = web, browserLabel: String? = "Brave"): PickerModel {
        val model = PickerModel(
            platform,
            backgroundScope,
            target,
            browserLabel,
            onFill = { filled += it },
            computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        return model
    }

    private fun PickerModel.state() = assertNotNull(view.value).state

    @Test
    fun `matching entries come first, all entries below`() = runTest {
        val state = model().state()
        assertEquals(web, state.target)
        assertEquals("Brave", state.browserLabel)
        assertEquals(listOf(paypal), state.matching)
        assertEquals(listOf(paypal.id), state.matchingIds.toList())
        assertEquals(listOf("Einkauf", "Finanzen"), state.sections.map { it.groupPath.single() })
        assertEquals(3, state.sections.sumOf { it.entries.size })
        assertNull(state.searchResults)
        assertEquals("Root", state.rootGroupName)
        assertNull(state.phishing)
    }

    @Test
    fun `search results are flat and keep the matching ids`() = runTest {
        val model = model()
        model.onQueryChange("a")
        assertEquals("a", model.query.value)
        // The query is immediate, the results follow after the computation.
        assertNull(model.state().searchResults)
        runCurrent()
        val state = model.state()
        assertEquals("a", state.query)
        assertEquals(listOf(paypal, bank, notes).map { it.id }.toSet(), state.searchResults.orEmpty().map { it.id }.toSet())
        assertEquals(setOf(paypal.id), state.matchingIds)
        assertEquals(listOf(paypal), state.matching)
    }

    @Test
    fun `blank query counts as no search`() = runTest {
        val model = model()
        model.onQueryChange("   ")
        runCurrent()
        assertNull(model.state().searchResults)
    }

    @Test
    fun `a matching pick fills at once`() = runTest {
        val model = model()
        model.onPick(paypal)
        assertEquals(listOf(paypal), filled)
        assertNull(model.state().phishing)
    }

    @Test
    fun `a mismatching pick on a web page asks first`() = runTest {
        val model = model(fakePage)
        model.onPick(paypal)
        runCurrent()
        assertTrue(filled.isEmpty())
        val warning = assertNotNull(model.state().phishing)
        assertEquals(paypal, warning.entry)
        assertEquals("evil.example", warning.pageDomain)
        assertEquals("paypal.com", warning.entryDomain)
    }

    @Test
    fun `an entry without url on a web page asks first, without entry domain`() = runTest {
        val model = model()
        model.onPick(notes)
        runCurrent()
        assertTrue(filled.isEmpty())
        val warning = assertNotNull(model.state().phishing)
        assertEquals("paypal.com", warning.pageDomain)
        assertNull(warning.entryDomain)
    }

    @Test
    fun `fill anyway fills and closes the warning`() = runTest {
        val model = model(fakePage)
        model.onPick(bank)
        runCurrent()
        model.onPhishingFillAnyway()
        runCurrent()
        assertEquals(listOf(bank), filled)
        assertNull(model.state().phishing)
    }

    @Test
    fun `dismissing the warning fills nothing`() = runTest {
        val model = model(fakePage)
        model.onPick(bank)
        runCurrent()
        model.onPhishingDismiss()
        runCurrent()
        assertTrue(filled.isEmpty())
        assertNull(model.state().phishing)
        // Filling anyway after the dismissal has nothing to fill.
        model.onPhishingFillAnyway()
        assertTrue(filled.isEmpty())
    }

    @Test
    fun `an app target never gets a warning`() = runTest {
        val model = model(app, browserLabel = null)
        assertNull(model.state().browserLabel)
        model.onPick(bank)
        model.onPick(notes)
        runCurrent()
        assertEquals(listOf(bank, notes), filled)
        assertNull(model.state().phishing)
    }

    @Test
    fun `nothing is kept after a lock`() = runTest {
        val model = model(fakePage)
        model.onQueryChange("pay")
        model.onPick(paypal)
        runCurrent()
        assertNotNull(model.state().phishing)

        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        runCurrent()
        assertNull(model.view.value)
        assertEquals("", model.query.value)

        // A new unlock starts clean: no query, no warning.
        platform.sessionFlow.value = unlockedView(testVault(listOf(paypal, bank, notes)))
        runCurrent()
        val state = model.state()
        assertEquals("", state.query)
        assertNull(state.searchResults)
        assertNull(state.phishing)
        assertTrue(filled.isEmpty())
    }

    @Test
    fun `the view follows a newer vault`() = runTest {
        val model = model()
        val added = testEntry("Login", url = "https://paypal.com")
        platform.sessionFlow.value = unlockedView(testVault(listOf(paypal, bank, notes, added)), version = 2)
        runCurrent()
        assertEquals(setOf(paypal.id, added.id), model.state().matching.map { it.id }.toSet())
    }
}
