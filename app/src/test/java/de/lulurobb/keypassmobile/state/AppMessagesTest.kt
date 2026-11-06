package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.ui.common.UiMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppMessagesTest {

    private val messages = AppMessages()

    @Test
    fun `post shows a message, a new one replaces it, dismiss clears it`() {
        assertNull(messages.message.value)
        messages.post(UiMessage.Locked)
        assertEquals(UiMessage.Locked, messages.message.value)
        messages.post(UiMessage.SyncUpdated)
        assertEquals(UiMessage.SyncUpdated, messages.message.value)
        messages.dismiss()
        assertNull(messages.message.value)
    }
}
