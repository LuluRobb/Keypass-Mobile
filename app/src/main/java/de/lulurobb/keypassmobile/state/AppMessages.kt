package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.platform.clipboard.ClipboardStatus
import de.lulurobb.keypassmobile.ui.common.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /**
     * Shows the clipboard countdown and its end:
     * - Holding: "Kopiert, wird in n s gelöscht", updated with every status change.
     *   A new copy replaces any message; the ticks of a running countdown only
     *   replace the countdown itself or an empty slot, so they do not wipe other
     *   messages every second.
     * - Holding then Cleared: "Zwischenablage gelöscht" once, except when the
     *   session is locked (the lock message wins, a leftover countdown is removed).
     * - Idle, and Cleared without a countdown seen before: nothing.
     *
     * The platform clears the clipboard at the start of a lock, possibly before the
     * session state shows Locked. Then "Zwischenablage gelöscht" is posted first and the
     * lock message replaces it. Locks without a message (swipe-away) leave it standing,
     * harmless because the app is gone.
     */
    fun bindClipboard(
        clipboard: StateFlow<ClipboardStatus>,
        session: StateFlow<SessionView>,
        scope: CoroutineScope,
    ): Job = scope.launch {
        var previous: ClipboardStatus = ClipboardStatus.Idle
        clipboard.collect { status ->
            val before = previous
            previous = status
            when (status) {
                ClipboardStatus.Idle -> Unit
                is ClipboardStatus.Holding -> {
                    val current = mutableMessage.value
                    val continuing = before is ClipboardStatus.Holding &&
                        before.value == status.value &&
                        status.secondsLeft <= before.secondsLeft
                    if (!continuing || current == null || current is UiMessage.Copied) {
                        mutableMessage.value = clipboardMessage(status)
                    }
                }
                is ClipboardStatus.Cleared -> if (before is ClipboardStatus.Holding) {
                    if (session.value is SessionView.Locked) {
                        if (mutableMessage.value is UiMessage.Copied) mutableMessage.value = null
                    } else {
                        mutableMessage.value = clipboardMessage(status)
                    }
                }
            }
        }
    }
}
