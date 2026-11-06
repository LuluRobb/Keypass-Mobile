package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one snackbar slot of an activity. Every screen shows [message] and calls
 * [dismiss] when the message timed out; state holders [post] into it. A new
 * message replaces the current one.
 */
class AppMessages {

    private val mutableMessage = MutableStateFlow<UiMessage?>(null)

    val message: StateFlow<UiMessage?> = mutableMessage.asStateFlow()

    fun post(message: UiMessage) {
        mutableMessage.value = message
    }

    /** Removes the current message (the screen calls it after the timeout). */
    fun dismiss() {
        mutableMessage.value = null
    }
}
