package de.lulurobb.keypassmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import de.lulurobb.keypassmobile.state.AppController
import de.lulurobb.keypassmobile.state.AppScreen
import de.lulurobb.keypassmobile.state.FingerprintPrompter
import de.lulurobb.keypassmobile.ui.detail.DetailRoute
import de.lulurobb.keypassmobile.ui.list.ListRoute
import de.lulurobb.keypassmobile.ui.settings.SettingsRoute
import de.lulurobb.keypassmobile.ui.setup.BrowseRoute
import de.lulurobb.keypassmobile.ui.setup.ServerRoute
import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import de.lulurobb.keypassmobile.ui.setup.SourceFlowScreen
import de.lulurobb.keypassmobile.ui.setup.SystemSetupRoute
import de.lulurobb.keypassmobile.ui.unlock.UnlockRoute

/**
 * The screen of [AppController.screen] with its route composable. Every screen shares the
 * messages of the controller. [resumed] is true while the activity is in the foreground.
 * The system pages are activity-only, so the activity passes them in. [onExit] is system
 * back at a root screen (the activity moves the task to the back).
 */
@Composable
fun AppRoot(
    controller: AppController,
    resumed: Boolean,
    prompter: FingerprintPrompter,
    onOpenAutofillSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val screen by controller.screen.collectAsState()
    val messages = controller.messages
    val back: () -> Unit = { if (!controller.onBack()) onExit() }
    // Registered first, so the BackHandlers of the source flow routes (enabled) take precedence.
    BackHandler(onBack = back)
    when (val current = screen) {
        is AppScreen.Source -> key(current.model) {
            when (current.route.screen) {
                SourceFlowScreen.Server -> ServerRoute(
                    model = current.model,
                    messages = messages,
                    // First run: no arrow, system back leaves the app.
                    onBack = if (current.route.mode == SourceFlowMode.FirstRun) null else back,
                )
                SourceFlowScreen.Browse -> BrowseRoute(current.model, messages, onLeave = back)
            }
        }
        is AppScreen.SystemSetup -> key(current.model) {
            SystemSetupRoute(current.model, messages, onOpenAutofillSettings, onRequestNotifications)
        }
        is AppScreen.Unlock -> key(current.model) {
            UnlockRoute(
                model = current.model,
                messages = messages,
                resumed = resumed,
                prompter = prompter,
                onBack = if (current.route.firstRun) back else null,
                onCancelFill = null,
                onChooseSource = controller::chooseSource,
            )
        }
        is AppScreen.EntryList -> key(current.model) {
            ListRoute(current.model, messages, onEntryClick = controller::openEntry, onSettings = controller::openSettings)
        }
        is AppScreen.EntryDetail -> key(current.model) {
            DetailRoute(current.model, messages, onBack = back)
        }
        is AppScreen.Settings -> key(current.model) {
            SettingsRoute(
                model = current.model,
                messages = messages,
                onBack = back,
                onEditSource = controller::editSource,
                onOpenAutofillSettings = onOpenAutofillSettings,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )
        }
    }
}
