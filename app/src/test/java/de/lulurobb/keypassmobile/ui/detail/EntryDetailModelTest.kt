package de.lulurobb.keypassmobile.ui.detail

import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.state.FakeAppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.testEntry
import de.lulurobb.keypassmobile.state.testVault
import de.lulurobb.keypassmobile.state.unlockedView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailModelTest {

    private val paypal = testEntry("PayPal", username = "anna")
    private val bank = testEntry("Bank")
    private val platform = FakeAppPlatform(session = unlockedView(testVault(listOf(paypal, bank), rootName = "Meine DB")))
    private var missing = 0

    private fun TestScope.model(id: UUID = paypal.id): EntryDetailModel {
        val model = EntryDetailModel(platform, backgroundScope, id) { missing++ }
        runCurrent()
        return model
    }

    @Test
    fun `finds the entry by id with root name and clipboard seconds`() = runTest {
        val model = model()
        val state = assertNotNull(model.view.value).state
        assertSame(paypal, state.entry)
        assertEquals("Meine DB", state.rootGroupName)
        assertEquals(ClipboardTimeout.DEFAULT.duration.seconds.toInt(), state.clipboardSeconds)
        assertEquals(0, missing)
    }

    @Test
    fun `clipboard seconds follow the setting`() = runTest {
        val model = model()
        platform.settingsFlow.value = platform.settingsFlow.value.copy(clipboardTimeout = ClipboardTimeout.SECONDS_60)
        runCurrent()
        assertEquals(60, assertNotNull(model.view.value).state.clipboardSeconds)
    }

    @Test
    fun `a new version replaces the entry`() = runTest {
        val model = model()
        val renamed = testEntry("PayPal neu", id = paypal.id)
        platform.sessionFlow.value = unlockedView(testVault(listOf(renamed)), version = 2)
        runCurrent()
        assertSame(renamed, assertNotNull(model.view.value).state.entry)
        assertEquals(0, missing)
    }

    @Test
    fun `unknown entry reports missing once`() = runTest {
        val model = model(UUID.randomUUID())
        assertNull(model.view.value)
        assertEquals(1, missing)
        platform.sessionFlow.value = unlockedView(testVault(listOf(bank)), version = 2)
        runCurrent()
        assertEquals(1, missing)
    }

    @Test
    fun `entry removed by a new version reports missing`() = runTest {
        val model = model()
        platform.sessionFlow.value = unlockedView(testVault(listOf(bank)), version = 2)
        runCurrent()
        assertNull(model.view.value)
        assertEquals(1, missing)
    }

    @Test
    fun `lock drops the view without reporting missing`() = runTest {
        val model = model()
        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        runCurrent()
        assertNull(model.view.value)
        assertEquals(0, missing)
    }

    @Test
    fun `copy buttons and lock call the platform`() = runTest {
        val model = model()
        model.onCopyUsername()
        model.onCopyPassword()
        model.onLock()
        assertEquals(
            listOf("copyUsername:${paypal.id}", "copyPassword:${paypal.id}", "lock"),
            platform.calls,
        )
    }
}
