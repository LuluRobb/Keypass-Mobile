package de.lulurobb.keypassmobile.ui.setup

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.ui.common.PickFailure
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.BusyOverlay
import de.lulurobb.keypassmobile.ui.dialogs.OlderVersionDialog
import de.lulurobb.keypassmobile.ui.dialogs.OlderVersionInfo
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Source choice (setup step 1 and "Quelle verwalten"), the busy view while a picked file is read, the
 * rejected-file snackbar with "Neu wählen", the "Ältere Version" dialog and the picker contract (DS-08 to DS-15).
 */
@RunWith(AndroidJUnit4::class)
class SourceScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private var smb = 0
    private var file = 0
    private var back = 0
    private var action = 0

    private fun showChoose(message: UiMessage? = null) {
        rule.setContent {
            KeyPassTheme {
                ChooseScreen(onSmb = { smb++ }, onFile = { file++ }, message = message, onMessageAction = { action++ })
            }
        }
    }

    private fun showManage(kind: SourceKind, message: UiMessage? = null) {
        rule.setContent {
            KeyPassTheme {
                ManageSourceScreen(
                    current = kind,
                    onSmb = { smb++ },
                    onFile = { file++ },
                    onBack = { back++ },
                    message = message,
                    onMessageAction = { action++ },
                )
            }
        }
    }

    // ---- setup step 1 ----

    @Test
    fun chooseTextsAndStep() {
        showChoose()
        rule.onNodeWithText("Einrichten").assertIsDisplayed()
        rule.onNodeWithText("Schritt 1 von 4").assertIsDisplayed()
        rule.onNodeWithText("Wo liegt deine Datenbank?").assertIsDisplayed()
        rule.onNodeWithText("KeyPass Mobile liest die KDBX-Datei nur und behält eine verschlüsselte Kopie für unterwegs.")
            .assertIsDisplayed()
        rule.onNodeWithText("SMB-Freigabe").assertIsDisplayed()
        rule.onNodeWithText("NAS oder Windows-PC im Heimnetz. Unterwegs ohne VPN gilt die Kopie auf dem Handy.")
            .assertIsDisplayed()
        rule.onNodeWithText("Datei auf dem Handy").assertIsDisplayed()
        rule.onNodeWithText("Zum Beispiel in einem Ordner, den Syncthing aktuell hält. Du wählst sie in der Dateiauswahl von Android.")
            .assertIsDisplayed()
    }

    @Test
    fun chooseHasNoBackArrowAndTwoCards() {
        showChoose()
        rule.onNodeWithContentDescription("Zurück").assertDoesNotExist()
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test
    fun chooseCardsCallTheirCallbacks() {
        showChoose()
        rule.onNodeWithText("SMB-Freigabe").performClick()
        assertEquals(1, smb)
        assertEquals(0, file)
        rule.onNodeWithText("Datei auf dem Handy").performClick()
        assertEquals(1, file)
    }

    // ---- Quelle verwalten ----

    @Test
    fun manageSmbRows() {
        showManage(SourceKind.SMB)
        rule.onNodeWithText("Quelle verwalten").assertIsDisplayed()
        rule.onNodeWithText("SMB-Freigabe ändern").assertIsDisplayed()
        rule.onNodeWithText("Zu Datei wechseln").assertIsDisplayed()
        // The back arrow and exactly two rows; no sublines, no choice texts.
        rule.onAllNodes(hasClickAction()).assertCountEquals(3)
        rule.onNodeWithText("NAS oder Windows-PC im Heimnetz. Unterwegs ohne VPN gilt die Kopie auf dem Handy.").assertDoesNotExist()
        rule.onNodeWithText("Wo liegt deine Datenbank?").assertDoesNotExist()
        rule.onNodeWithText("Schritt 1 von 4").assertDoesNotExist()

        rule.onNodeWithText("SMB-Freigabe ändern").performClick()
        rule.onNodeWithText("Zu Datei wechseln").performClick()
        assertEquals(1, smb)
        assertEquals(1, file)
    }

    @Test
    fun manageFileRows() {
        showManage(SourceKind.FILE)
        rule.onNodeWithText("Andere Datei wählen").assertIsDisplayed()
        rule.onNodeWithText("Zu SMB-Freigabe wechseln").assertIsDisplayed()
        rule.onAllNodes(hasClickAction()).assertCountEquals(3)

        rule.onNodeWithText("Andere Datei wählen").performClick()
        rule.onNodeWithText("Zu SMB-Freigabe wechseln").performClick()
        assertEquals(1, file)
        assertEquals(1, smb)
    }

    @Test
    fun manageBackArrow() {
        showManage(SourceKind.SMB)
        rule.onNodeWithContentDescriptionBack().performClick()
        assertEquals(1, back)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionBack() =
        onNodeWithContentDescription("Zurück")

    // ---- snackbar ----

    @Test
    fun rejectedFileSnackbarOnTheChoiceCallsNeuWaehlen() {
        showChoose(UiMessage.PickFailed(PickFailure.NotKdbx, "liesmich.txt"))
        rule.onNodeWithText("„liesmich.txt“ ist keine KeePass-Datenbank.").assertIsDisplayed()
        rule.onNodeWithText("Neu wählen").performClick()
        assertEquals(1, action)
    }

    @Test
    fun rejectedFileSnackbarOnTheManageScreen() {
        showManage(SourceKind.FILE, UiMessage.PickFailed(PickFailure.Unreadable, "a.kdbx"))
        rule.onNodeWithText("„a.kdbx“ ließ sich nicht lesen.").assertIsDisplayed()
        rule.onNodeWithText("Neu wählen").performClick()
        assertEquals(1, action)
    }

    // ---- busy view ----

    @Test
    fun busyViewText() {
        rule.setContent { KeyPassTheme { BusyOverlay("Lese Datei …") } }
        rule.onNodeWithText("Lese Datei …").assertIsDisplayed()
    }

    // ---- older version dialog ----

    @Test
    fun olderDialogButtons() {
        var anyway = 0
        var cancelled = 0
        rule.setContent {
            KeyPassTheme {
                OlderVersionDialog(
                    OlderVersionInfo(
                        "Passwoerter.kdbx",
                        LocalDateTime.of(2026, 9, 12, 18, 5),
                        LocalDateTime.of(2026, 10, 31, 21, 38),
                    ),
                    onUseAnyway = { anyway++ },
                    onCancel = { cancelled++ },
                )
            }
        }
        rule.onNodeWithText("Ältere Version").assertIsDisplayed()
        rule.onNodeWithText("Abbrechen").performClick()
        rule.onNodeWithText("Trotzdem verwenden").performClick()
        assertEquals(1, cancelled)
        assertEquals(1, anyway)
    }

    // ---- picker contract ----

    @Test
    fun pickerIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = "content://com.android.externalstorage.documents/document/primary%3ASyncthing%2FKeePass"
        val intent = OpenKdbxDocument().createIntent(context, start)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", intent.type)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertEquals(Uri.parse(start), intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(flags, intent.flags and flags)
    }

    @Test
    fun pickerIntentWithoutStartFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = OpenKdbxDocument().createIntent(context, null)
        assertTrue(!intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
    }

    @Test
    fun pickerResult() {
        val uri = Uri.parse("content://docs/document/1")
        val contract = OpenKdbxDocument()
        assertEquals(uri, contract.parseResult(android.app.Activity.RESULT_OK, Intent().setData(uri)))
        assertEquals(null, contract.parseResult(android.app.Activity.RESULT_CANCELED, Intent().setData(uri)))
        assertNotEquals(uri, contract.parseResult(android.app.Activity.RESULT_OK, Intent()))
    }
}
