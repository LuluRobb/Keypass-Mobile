package de.lulurobb.keypassmobile.ui.detail

import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.FakeAppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.testEntry
import de.lulurobb.keypassmobile.state.testVault
import de.lulurobb.keypassmobile.state.unlockedView
import de.lulurobb.keypassmobile.ui.common.CopiedValue
import de.lulurobb.keypassmobile.ui.common.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailModelTest {

    private val paypal = testEntry("PayPal", username = "anna")
    private val bank = testEntry("Bank")
    private val platform = FakeAppPlatform(session = unlockedView(testVault(listOf(paypal, bank), rootName = "Meine DB")))
    private val messages = AppMessages()
    private var missing = 0

    private fun TestScope.model(id: UUID = paypal.id): EntryDetailModel {
        val model = EntryDetailModel(platform, backgroundScope, id, messages) { missing++ }
        runCurrent()
        return model
    }

    @Test
    fun `finds the entry by id with root name`() = runTest {
        val model = model()
        val state = assertNotNull(model.view.value).state
        assertSame(paypal, state.entry)
        assertEquals("Meine DB", state.rootGroupName)
        assertEquals(0, missing)
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

    private fun EntryDetailModel.shown(): String? = view.value?.state?.shownPassword?.let { String(it) }

    private fun CharArray.isWiped() = all { it == '\u0000' }

    @Test
    fun `the password is hidden by default and not read`() = runTest {
        platform.passwords[paypal.id] = "geheim"
        val model = model()
        assertNull(model.view.value?.state?.shownPassword)
        assertTrue(platform.calls.none { it.startsWith("revealPassword") })
    }

    @Test
    fun `the toggle reveals the password and hides it again with a wiped copy`() = runTest {
        platform.passwords[paypal.id] = "geheim"
        val model = model()
        model.onTogglePassword()
        runCurrent()
        assertEquals("geheim", model.shown())
        assertEquals(listOf("revealPassword:${paypal.id}"), platform.calls)

        model.onTogglePassword()
        runCurrent()
        assertNull(model.view.value?.state?.shownPassword)
        assertTrue(platform.revealed.single().isWiped())
    }

    @Test
    fun `an entry without a readable password stays hidden`() = runTest {
        val model = model()
        model.onTogglePassword()
        runCurrent()
        assertNull(model.view.value?.state?.shownPassword)
    }

    @Test
    fun `lock hides the password and wipes the copy`() = runTest {
        platform.passwords[paypal.id] = "geheim"
        val model = model()
        model.onTogglePassword()
        runCurrent()
        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        runCurrent()
        assertNull(model.view.value)
        assertTrue(platform.revealed.single().isWiped())

        // Unlocked again: hidden.
        platform.sessionFlow.value = unlockedView(testVault(listOf(paypal, bank)), version = 2)
        runCurrent()
        assertNull(model.view.value?.state?.shownPassword)
    }

    @Test
    fun `a new vault version hides the password`() = runTest {
        platform.passwords[paypal.id] = "geheim"
        val model = model()
        model.onTogglePassword()
        runCurrent()
        platform.sessionFlow.value = unlockedView(testVault(listOf(paypal, bank)), version = 2)
        runCurrent()
        assertNull(model.view.value?.state?.shownPassword)
        assertTrue(platform.revealed.single().isWiped())
    }

    @Test
    fun `hidePassword for the stop of the activity hides and wipes`() = runTest {
        platform.passwords[paypal.id] = "geheim"
        val model = model()
        model.onTogglePassword()
        runCurrent()
        model.hidePassword()
        runCurrent()
        assertNull(model.view.value?.state?.shownPassword)
        assertTrue(platform.revealed.single().isWiped())
        // Hiding twice is harmless; the next toggle reveals again with a fresh copy.
        model.hidePassword()
        model.onTogglePassword()
        runCurrent()
        assertEquals("geheim", model.shown())
        assertEquals(2, platform.revealed.size)
    }

    @Test
    fun `leaving the screen ends the scope and wipes the password`() = runTest {
        platform.passwords[paypal.id] = "geheim"
        val own = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val model = EntryDetailModel(platform, own, paypal.id, messages) { missing++ }
        runCurrent()
        model.onTogglePassword()
        runCurrent()
        assertEquals("geheim", model.shown())
        own.cancel()
        runCurrent()
        assertTrue(platform.revealed.single().isWiped())
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

    @Test
    fun `a copy posts what was copied`() = runTest {
        val model = model()
        model.onCopyUsername()
        assertEquals(UiMessage.Copied(CopiedValue.Username), messages.message.value)
        model.onCopyPassword()
        assertEquals(UiMessage.Copied(CopiedValue.Password), messages.message.value)
    }

    @Test
    fun `a failed copy posts nothing`() = runTest {
        platform.copyResult = false
        val model = model()
        model.onCopyUsername()
        model.onCopyPassword()
        assertNull(messages.message.value)
    }
}
