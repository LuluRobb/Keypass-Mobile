package de.lulurobb.keypassmobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.ui.common.SourceAction

/**
 * Settings screen on its [SettingsModel]. Renders nothing while there is no
 * source (the model then calls its onNoSource). The system intents are
 * activity-only, so the caller passes them in.
 */
@Composable
fun SettingsRoute(
    model: SettingsModel,
    messages: AppMessages,
    onBack: () -> Unit,
    onEditSource: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSourceAction: (SourceAction) -> Unit = {},
    onMessageAction: () -> Unit = {},
) {
    val state by model.state.collectAsState()
    val message by messages.message.collectAsState()
    state?.let {
        SettingsScreen(
            state = it,
            onBack = onBack,
            onEditSource = onEditSource,
            onSyncNow = model::onSyncNow,
            onFingerprintChange = model::onFingerprintChange,
            onPasswordIntervalChange = model::onPasswordIntervalChange,
            onAutoLockChange = model::onAutoLockChange,
            onOpenAutofillSettings = onOpenAutofillSettings,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onSourceAction = onSourceAction,
            message = message,
            onMessageDismissed = messages::dismiss,
            onMessageAction = onMessageAction,
        )
    }
}
