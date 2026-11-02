package de.lulurobb.keypassmobile.ui.setup

import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.FakeAppPlatform
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.SetupStep
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemSetupModelTest {

    private val platform = FakeAppPlatform()
    private val messages = AppMessages()
    private var stored = false
    private val model = SystemSetupModel(platform, messages) { stored }

    @Test
    fun `initial state reads the system and shows step 4`() {
        assertEquals(SystemSetupState(autofillSelected = false, notificationsAllowed = false), model.state.value)
        assertEquals(SetupStep(4), model.state.value.step)

        platform.autofillSelected = true
        val second = SystemSetupModel(platform, messages) { stored }
        assertTrue(second.state.value.autofillSelected)
        assertFalse(second.state.value.notificationsAllowed)
    }

    @Test
    fun `refresh reads both system values again`() {
        platform.autofillSelected = true
        platform.notificationsAllowed = true
        assertFalse(model.state.value.autofillSelected)
        model.refresh()
        assertTrue(model.state.value.autofillSelected)
        assertTrue(model.state.value.notificationsAllowed)
        platform.autofillSelected = false
        model.refresh()
        assertFalse(model.state.value.autofillSelected)
    }

    @Test
    fun `finish completes the setup and says done without fingerprint`() {
        model.onFinish()
        assertTrue("completeSetup" in platform.calls)
        assertTrue(platform.settingsFlow.value.setupCompleted)
        assertEquals(UiMessage.SetupDone(fingerprint = false), messages.message.value)
    }

    @Test
    fun `finish mentions the fingerprint only when it is on and stored`() {
        for ((enabled, isStored, expected) in listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false),
        )) {
            messages.dismiss()
            platform.settingsFlow.value = platform.settingsFlow.value.copy(fingerprintEnabled = enabled)
            stored = isStored
            model.onFinish()
            assertEquals(UiMessage.SetupDone(expected), messages.message.value, "enabled=$enabled stored=$isStored")
        }
    }

    @Test
    fun `no message before finish`() {
        assertNull(messages.message.value)
    }
}
