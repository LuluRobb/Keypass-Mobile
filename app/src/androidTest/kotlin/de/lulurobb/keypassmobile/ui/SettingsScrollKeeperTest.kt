package de.lulurobb.keypassmobile.ui

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.state.AppRoute
import de.lulurobb.keypassmobile.ui.components.SyncState
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import de.lulurobb.keypassmobile.ui.settings.SettingsScreen
import de.lulurobb.keypassmobile.ui.settings.SettingsState
import de.lulurobb.keypassmobile.ui.settings.SourceEntry
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings come back at their scroll position after the screen opened from them (About, source
 * change) and start at the top again after any other route. Same wiring as [AppRoot]: the settings run
 * inside the holder of [rememberScreenStateHolder].
 */
@RunWith(AndroidJUnit4::class)
class SettingsScrollKeeperTest {

    @get:Rule
    val rule = createComposeRule()

    private var route by mutableStateOf<AppRoute>(AppRoute.Settings)

    private fun show() {
        rule.setContent {
            KeyPassTheme {
                val holder = rememberScreenStateHolder(route)
                if (route == AppRoute.Settings) {
                    holder.SaveableStateProvider(SettingsStateKey) {
                        SettingsScreen(
                            state = SettingsState(
                                source = SourceEntry.File("Passwoerter.kdbx", null),
                                sync = SyncState(SyncStatus.Current(null), kind = SourceKind.FILE),
                                fingerprintEnabled = true,
                                passwordDueOn = null,
                                passwordIntervalDays = 7,
                                autoLockSeconds = 120,
                                autofillSelected = true,
                                notificationsAllowed = true,
                                versionName = "0.3.0",
                            ),
                            onBack = {},
                            onEditSource = {},
                            onSyncNow = {},
                            onFingerprintChange = {},
                            onPasswordIntervalChange = {},
                            onAutoLockChange = {},
                            onOpenAutofillSettings = {},
                            onOpenNotificationSettings = {},
                            onOpenAbout = { route = AppRoute.About },
                            // A short window, so the settings always have to scroll.
                            modifier = Modifier.height(500.dp),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun settingsKeepTheScrollPositionAcrossTheAboutScreen() {
        show()
        rule.onNodeWithText("Über KeyPass Mobile").performScrollTo()
        rule.onNodeWithText("Quelle verwalten").assertIsNotDisplayed()

        rule.onNodeWithText("Über KeyPass Mobile").performClick()
        rule.waitForIdle()
        route = AppRoute.Settings
        rule.waitForIdle()

        rule.onNodeWithText("Über KeyPass Mobile").assertIsDisplayed()
        rule.onNodeWithText("Quelle verwalten").assertIsNotDisplayed()
    }

    @Test
    fun settingsStartAtTheTopAfterLeavingTheirStack() {
        show()
        rule.onNodeWithText("Über KeyPass Mobile").performScrollTo()

        route = AppRoute.EntryList
        rule.waitForIdle()
        route = AppRoute.Settings
        rule.waitForIdle()

        rule.onNodeWithText("Quelle verwalten").assertIsDisplayed()
        rule.onNodeWithText("Über KeyPass Mobile").assertIsNotDisplayed()
    }
}
