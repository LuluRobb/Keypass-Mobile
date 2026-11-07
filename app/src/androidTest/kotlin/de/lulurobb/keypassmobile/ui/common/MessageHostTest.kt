package de.lulurobb.keypassmobile.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Snackbar texts and the action "Neu wählen" of a rejected file (DS-12). */
@RunWith(AndroidJUnit4::class)
class MessageHostTest {

    @get:Rule
    val rule = createComposeRule()

    private var dismissed = 0
    private var actions = 0

    private fun show(message: UiMessage) {
        rule.setContent {
            KeyPassTheme {
                MessageHost(message, onDismissed = { dismissed++ }, onAction = { actions++ })
            }
        }
    }

    @Test
    fun pickFailedTextsAndAction() {
        show(UiMessage.PickFailed(PickFailure.NotKdbx, "liesmich.txt"))
        rule.onNodeWithText("„liesmich.txt“ ist keine KeePass-Datenbank.").assertIsDisplayed()
        rule.onNodeWithText("Neu wählen").performClick()
        assertEquals(1, actions)
        assertEquals(1, dismissed)
    }

    @Test
    fun actionSitsRightOfMessageWithoutOverlap() {
        show(UiMessage.PickFailed(PickFailure.NotKdbx, "liesmich.txt"))
        val message = rule.onNodeWithText("„liesmich.txt“ ist keine KeePass-Datenbank.").getBoundsInRoot()
        val action = rule.onNodeWithText("Neu wählen").getBoundsInRoot()
        assertTrue("message $message action $action", message.left < action.left)
        assertTrue("message $message action $action", message.right <= action.left)
        assertTrue("message $message action $action", action.right > message.right)
    }

    @Test
    fun longMessageWrapsAndDoesNotOverlapAction() {
        val name = "ein-sehr-langer-dateiname-der-umbrechen-muss-in-der-snackbar-1234567890.kdbx"
        show(UiMessage.PickFailed(PickFailure.NotKdbx, name))
        val message = rule.onNodeWithText("„$name“ ist keine KeePass-Datenbank.").getBoundsInRoot()
        val action = rule.onNodeWithText("Neu wählen").getBoundsInRoot()
        val separate = message.right <= action.left || message.bottom <= action.top || action.bottom <= message.top
        assertTrue("message $message action $action", separate)
    }

    @Test
    fun pickFailedUnreadable() {
        show(UiMessage.PickFailed(PickFailure.Unreadable, "a.kdbx"))
        rule.onNodeWithText("„a.kdbx“ ließ sich nicht lesen.").assertIsDisplayed()
    }

    @Test
    fun pickFailedNoLastingAccess() {
        show(UiMessage.PickFailed(PickFailure.NoLastingAccess, "a.kdbx"))
        rule.onNodeWithText("Android gibt keinen dauerhaften Zugriff auf „a.kdbx“.").assertIsDisplayed()
    }

    @Test
    fun otherMessagesHaveNoAction() {
        show(UiMessage.SourceSaved)
        rule.onNodeWithText("Quelle gespeichert.").assertIsDisplayed()
        rule.onNodeWithText("Neu wählen").assertDoesNotExist()
    }

    @Test
    fun fileSyncMessages() {
        show(UiMessage.SyncFileNotFound)
        rule.onNodeWithText("Datei nicht gefunden. Die Offline-Kopie bleibt geöffnet.").assertIsDisplayed()
    }
}
