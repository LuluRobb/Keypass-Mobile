package de.lulurobb.keypassmobile.ui.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/** Dialog "Ältere Version" (DS-14): texts, date format and the two buttons. */
@RunWith(AndroidJUnit4::class)
class OlderVersionDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private var anyway = 0
    private var cancelled = 0

    private fun show() {
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
    }

    @Test
    fun textsAndDates() {
        show()
        rule.onNodeWithText("Ältere Version").assertIsDisplayed()
        rule.onNodeWithText("„Passwoerter.kdbx“ ist vom 12.09.2026, 18:05. Geöffnet ist der Stand vom 31.10.2026, 21:38.")
            .assertIsDisplayed()
        rule.onNodeWithText("Abbrechen").assertIsDisplayed()
        rule.onNodeWithText("Trotzdem verwenden").assertIsDisplayed()
    }

    @Test
    fun buttonsCallTheirCallbacks() {
        show()
        rule.onNodeWithText("Abbrechen").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, anyway)
        rule.onNodeWithText("Trotzdem verwenden").performClick()
        assertEquals(1, anyway)
    }
}
