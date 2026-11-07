package de.lulurobb.keypassmobile.ui.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiMessageTest {

    @Test
    fun `only a rejected pick has an action`() {
        for (reason in PickFailure.entries) {
            assertTrue(UiMessage.PickFailed(reason, "liesmich.txt").hasAction, "$reason")
        }
        val others = listOf(
            UiMessage.SourceSaved,
            UiMessage.SyncFileNotFound,
            UiMessage.SyncFileNoAccess,
            UiMessage.SyncFileUnchanged,
            UiMessage.SyncUpdatedFile,
            UiMessage.DatabaseLoadedFile,
            UiMessage.SyncFailed,
            UiMessage.Locked,
            UiMessage.InfoCopied,
            UiMessage.NoBrowser,
        )
        for (message in others) assertFalse(message.hasAction, "$message")
    }

    @Test
    fun `a rejected pick stays for ten seconds, other long texts for six, the rest for four`() {
        assertEquals(10_000L, UiMessage.PickFailed(PickFailure.NotKdbx, "a.txt").durationMillis())
        assertEquals(6_000L, UiMessage.SyncFileNotFound.durationMillis())
        assertEquals(6_000L, UiMessage.SyncFileNoAccess.durationMillis())
        assertEquals(6_000L, UiMessage.LockedSourceChanged.durationMillis())
        assertEquals(4_000L, UiMessage.SourceSaved.durationMillis())
        assertEquals(4_000L, UiMessage.SyncFileUnchanged.durationMillis())
        assertEquals(4_000L, UiMessage.InfoCopied.durationMillis())
        assertEquals(4_000L, UiMessage.NoBrowser.durationMillis())
    }
}
