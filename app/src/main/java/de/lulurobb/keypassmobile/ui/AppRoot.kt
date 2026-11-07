package de.lulurobb.keypassmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.state.AppController
import de.lulurobb.keypassmobile.state.AppScreen
import de.lulurobb.keypassmobile.state.FingerprintPrompter
import de.lulurobb.keypassmobile.ui.about.AboutRoute
import de.lulurobb.keypassmobile.ui.components.BusyOverlay
import de.lulurobb.keypassmobile.ui.detail.DetailRoute
import de.lulurobb.keypassmobile.ui.dialogs.OlderVersionDialog
import de.lulurobb.keypassmobile.ui.licenses.LicenseCatalog
import de.lulurobb.keypassmobile.ui.licenses.LicenseDetailRoute
import de.lulurobb.keypassmobile.ui.licenses.LicensesRoute
import de.lulurobb.keypassmobile.ui.licenses.loadLicenseEntries
import de.lulurobb.keypassmobile.ui.list.ListRoute
import de.lulurobb.keypassmobile.ui.settings.SettingsRoute
import de.lulurobb.keypassmobile.ui.setup.BrowseRoute
import de.lulurobb.keypassmobile.ui.setup.ChooseRoute
import de.lulurobb.keypassmobile.ui.setup.ServerRoute
import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import de.lulurobb.keypassmobile.ui.setup.SourceFlowScreen
import de.lulurobb.keypassmobile.ui.setup.SystemSetupRoute
import de.lulurobb.keypassmobile.ui.unlock.UnlockRoute

/**
 * The screen of [AppController.screen] with its route composable. Every screen shares the
 * messages of the controller. [resumed] is true while the activity is in the foreground.
 * The system pages and the links of the About screen and the license pages are activity-only, so the activity passes them in.
 * [onExit] is system back at a root screen (the activity moves the task to the back).
 *
 * On top of every screen: "Lese Datei ..." while a picked file is checked and saved (back does nothing)
 * and the "Ältere Version" dialog of a picked file. The action of a banner or status button
 * ([AppController.onSourceAction]) and "Neu wählen" of a rejected file (the message action) are
 * run by the controller.
 */
@Composable
fun AppRoot(
    controller: AppController,
    resumed: Boolean,
    prompter: FingerprintPrompter,
    onOpenAutofillSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenLink: (String) -> Unit,
    onExit: () -> Unit,
) {
    val screen by controller.screen.collectAsState()
    val busy by controller.filePick.busy.collectAsState()
    val olderDialog by controller.filePick.olderDialog.collectAsState()
    val messages = controller.messages
    val back: () -> Unit = { if (!controller.onBack()) onExit() }
    val repick: () -> Unit = controller.filePick::repickAfterError
    // The settings, the About screen and the licenses list keep their scroll position while a screen opened from them is on top.
    val screenState = rememberScreenStateHolder(screen.route)
    // The licenses are read once and shared by the list and the detail pages.
    val appContext = LocalContext.current.applicationContext
    val licenses = remember(appContext) { LicenseCatalog { loadLicenseEntries(appContext) } }
    // Registered first, so the BackHandlers of the source flow routes (enabled) take precedence.
    BackHandler(onBack = back)
    Box(Modifier.fillMaxSize()) {
        when (val current = screen) {
            is AppScreen.Source -> key(current.model) {
                when (current.route.screen) {
                    SourceFlowScreen.Choose -> ChooseRoute(
                        model = current.model,
                        messages = messages,
                        // First run: no arrow, system back leaves the app.
                        onBack = if (current.route.mode == SourceFlowMode.FirstRun) null else back,
                        onMessageAction = repick,
                    )
                    SourceFlowScreen.Server -> ServerRoute(
                        model = current.model,
                        messages = messages,
                        onBack = back,
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
                ListRoute(
                    current.model,
                    messages,
                    onEntryClick = controller::openEntry,
                    onSettings = controller::openSettings,
                    onSourceAction = controller::onSourceAction,
                    onMessageAction = repick,
                )
            }
            is AppScreen.EntryDetail -> key(current.model) {
                DetailRoute(current.model, messages, onBack = back)
            }
            is AppScreen.Settings -> key(current.model) {
                screenState.SaveableStateProvider(SettingsStateKey) {
                    SettingsRoute(
                        model = current.model,
                        messages = messages,
                        onBack = back,
                        onEditSource = controller::editSource,
                        onOpenAutofillSettings = onOpenAutofillSettings,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onOpenAbout = controller::openAbout,
                        onSourceAction = controller::onSourceAction,
                        onMessageAction = repick,
                    )
                }
            }
            is AppScreen.About -> key(current.model) {
                screenState.SaveableStateProvider(AboutStateKey) {
                    AboutRoute(
                        current.model,
                        messages,
                        onBack = back,
                        onOpenLink = onOpenLink,
                        onOpenLicenses = controller::openLicenses,
                    )
                }
            }
            is AppScreen.Licenses -> key(current.model) {
                screenState.SaveableStateProvider(LicensesStateKey) {
                    LicensesRoute(licenses, onBack = back, onOpen = { controller.openLicense(it.id) })
                }
            }
            is AppScreen.LicenseDetail -> key(current.model) {
                LicenseDetailRoute(licenses, current.route.id, messages, onBack = back, onOpenLink = onOpenLink)
            }
        }
        if (busy) BusyOverlay(stringResource(R.string.pick_reading))
    }
    // Registered after the screens: while a picked file is read, back does nothing.
    BackHandler(enabled = busy) { }
    olderDialog?.let { info ->
        OlderVersionDialog(
            info,
            onUseAnyway = controller.filePick::onOlderConfirm,
            onCancel = controller.filePick::onOlderCancel,
        )
    }
}
