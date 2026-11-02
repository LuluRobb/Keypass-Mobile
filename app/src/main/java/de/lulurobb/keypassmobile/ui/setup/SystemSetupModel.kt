package de.lulurobb.keypassmobile.ui.setup

import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.AppPlatform
import de.lulurobb.keypassmobile.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State holder of setup step 4. The two system settings are no flows, so
 * [refresh] re-reads them (call it in onResume, the user comes back from the
 * system pages). Opening those pages is activity-only and done by the route.
 *
 * [fingerprintStored] says whether the fingerprint key was stored by the
 * first unlock; with the switch on in the settings it decides which
 * "Einrichtung abgeschlossen" text is shown.
 */
class SystemSetupModel(
    private val platform: AppPlatform,
    private val messages: AppMessages,
    private val fingerprintStored: () -> Boolean,
) {
    private val mutableState = MutableStateFlow(read())

    val state: StateFlow<SystemSetupState> = mutableState.asStateFlow()

    fun refresh() {
        mutableState.value = read()
    }

    /** Ends the setup; the navigation follows the settings (setupCompleted). */
    fun onFinish() {
        platform.completeSetup()
        val fingerprint = platform.settings.value.fingerprintEnabled && fingerprintStored()
        messages.post(UiMessage.SetupDone(fingerprint))
    }

    private fun read() = SystemSetupState(
        autofillSelected = platform.isAutofillServiceSelected(),
        notificationsAllowed = platform.areNotificationsAllowed(),
    )
}
