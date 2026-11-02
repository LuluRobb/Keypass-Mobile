package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.platform.clipboard.ClipboardStatus
import de.lulurobb.keypassmobile.platform.clipboard.CopiedValue as PlatformCopiedValue
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.ui.common.CopiedValue
import de.lulurobb.keypassmobile.ui.common.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AppMessagesTest {

    private val clipboard = MutableStateFlow<ClipboardStatus>(ClipboardStatus.Idle)
    private val session = MutableStateFlow<SessionView>(unlockedView())
    private val messages = AppMessages()

    private fun holding(value: PlatformCopiedValue, seconds: Int) = ClipboardStatus.Holding(value, seconds)

    /** Sets the status and lets the collector see it (the flow would conflate otherwise). */
    private fun TestScope.emit(status: ClipboardStatus) {
        clipboard.value = status
        runCurrent()
    }

    private fun TestScope.bind() {
        messages.bindClipboard(clipboard, session, backgroundScope)
        runCurrent()
    }

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

    @Test
    fun `idle clipboard shows nothing`() = runTest {
        bind()
        assertNull(messages.message.value)
        emit(ClipboardStatus.Idle)
        assertNull(messages.message.value)
    }

    @Test
    fun `holding shows the countdown and updates it`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.USERNAME, 30))
        assertEquals(UiMessage.Copied(CopiedValue.Username, 30), messages.message.value)
        emit(holding(PlatformCopiedValue.USERNAME, 29))
        assertEquals(UiMessage.Copied(CopiedValue.Username, 29), messages.message.value)
        emit(holding(PlatformCopiedValue.USERNAME, 1))
        assertEquals(UiMessage.Copied(CopiedValue.Username, 1), messages.message.value)
    }

    @Test
    fun `password copy is named as password`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.PASSWORD, 15))
        assertEquals(UiMessage.Copied(CopiedValue.Password, 15), messages.message.value)
    }

    @Test
    fun `holding then cleared posts the end note once`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.PASSWORD, 1))
        emit(ClipboardStatus.Cleared(PlatformCopiedValue.PASSWORD))
        assertEquals(UiMessage.ClipboardCleared, messages.message.value)

        // After the snackbar timed out, the following Idle state posts nothing again.
        messages.dismiss()
        emit(ClipboardStatus.Idle)
        assertNull(messages.message.value)
    }

    @Test
    fun `cleared without a countdown seen before posts nothing`() = runTest {
        clipboard.value = ClipboardStatus.Cleared(PlatformCopiedValue.USERNAME)
        bind()
        assertNull(messages.message.value)
    }

    @Test
    fun `a second copy after the end note counts down and ends again`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.USERNAME, 1))
        emit(ClipboardStatus.Cleared(PlatformCopiedValue.USERNAME))
        emit(holding(PlatformCopiedValue.PASSWORD, 30))
        assertEquals(UiMessage.Copied(CopiedValue.Password, 30), messages.message.value)
        emit(ClipboardStatus.Cleared(PlatformCopiedValue.PASSWORD))
        assertEquals(UiMessage.ClipboardCleared, messages.message.value)
    }

    @Test
    fun `when locked the lock message wins over the end note`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.PASSWORD, 20))
        // Old platform order: the session says Locked, the lock message is posted, then the clipboard is cleared.
        session.value = SessionView.Locked(LockReason.User)
        messages.post(UiMessage.Locked)
        emit(ClipboardStatus.Cleared(PlatformCopiedValue.PASSWORD))
        assertEquals(UiMessage.Locked, messages.message.value)
    }

    @Test
    fun `when locked without a lock message the stale countdown is removed`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.PASSWORD, 20))
        session.value = SessionView.Locked(LockReason.TaskRemoved)
        emit(ClipboardStatus.Cleared(PlatformCopiedValue.PASSWORD))
        assertNull(messages.message.value)
    }

    @Test
    fun `lock message posted after the end note replaces it`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.PASSWORD, 20))
        // New platform order: the clipboard is cleared before the session shows Locked.
        emit(ClipboardStatus.Cleared(PlatformCopiedValue.PASSWORD))
        assertEquals(UiMessage.ClipboardCleared, messages.message.value)
        messages.post(UiMessage.AutoLocked(120))
        assertEquals(UiMessage.AutoLocked(120), messages.message.value)
    }

    @Test
    fun `countdown ticks do not overwrite another message`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.USERNAME, 30))
        messages.post(UiMessage.SyncUpdated)
        emit(holding(PlatformCopiedValue.USERNAME, 29))
        assertEquals(UiMessage.SyncUpdated, messages.message.value)

        // After the other message timed out, the countdown shows again.
        messages.dismiss()
        emit(holding(PlatformCopiedValue.USERNAME, 28))
        assertEquals(UiMessage.Copied(CopiedValue.Username, 28), messages.message.value)
    }

    @Test
    fun `a new copy replaces any message`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.USERNAME, 20))
        messages.post(UiMessage.SyncUpdated)
        // Other value during a running countdown.
        emit(holding(PlatformCopiedValue.PASSWORD, 30))
        assertEquals(UiMessage.Copied(CopiedValue.Password, 30), messages.message.value)

        messages.post(UiMessage.SyncUpdated)
        // Same value copied again: the countdown starts over (seconds go up).
        emit(holding(PlatformCopiedValue.PASSWORD, 15))
        emit(holding(PlatformCopiedValue.PASSWORD, 14))
        messages.post(UiMessage.SyncUpdated)
        emit(holding(PlatformCopiedValue.PASSWORD, 30))
        assertEquals(UiMessage.Copied(CopiedValue.Password, 30), messages.message.value)
    }

    @Test
    fun `the end note replaces another message`() = runTest {
        bind()
        emit(holding(PlatformCopiedValue.USERNAME, 1))
        messages.post(UiMessage.SyncUpdated)
        emit(ClipboardStatus.Cleared(PlatformCopiedValue.USERNAME))
        assertEquals(UiMessage.ClipboardCleared, messages.message.value)
    }
}
