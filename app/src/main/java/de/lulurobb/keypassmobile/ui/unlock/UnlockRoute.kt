package de.lulurobb.keypassmobile.ui.unlock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.FingerprintPrompter
import de.lulurobb.keypassmobile.ui.components.rememberSecretInput

/**
 * [UnlockScreen] on [model]. [resumed] is true while the activity is in the
 * foreground: the system prompt opens only then (EN-02), also when the offer
 * arrived in the background. [onBack] is the back button of the first-run setup,
 * [onCancelFill] the close button of the autofill unlock; both are null where the
 * screen has none. [onChooseSource] opens the source change from the "Offline-Kopie
 * beschädigt" notice. Leaving the screen after an unlock follows the session, not this route.
 */
@Composable
fun UnlockRoute(
    model: UnlockModel,
    messages: AppMessages,
    resumed: Boolean,
    prompter: FingerprintPrompter,
    onBack: (() -> Unit)?,
    onCancelFill: (() -> Unit)?,
    onChooseSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val promptToken by model.promptToken.collectAsState()
    val message by messages.message.collectAsState()
    val password = rememberSecretInput()
    val currentState by rememberUpdatedState(state)
    val currentPrompter by rememberUpdatedState(prompter)

    // The typed characters must not outlive the screen.
    DisposableEffect(password) { onDispose { password.clear() } }

    LaunchedEffect(promptToken, resumed) {
        val current = currentState
        if (promptToken > 0 && resumed && current.mode == UnlockMode.Fingerprint && !current.busy && current.problem == null) {
            model.onFingerprint(currentPrompter)
        }
    }

    UnlockScreen(
        state = state,
        password = password,
        onFingerprint = { model.onFingerprint(prompter) },
        onUsePassword = model::onUsePassword,
        onSubmitPassword = { model.onSubmit(password) },
        modifier = modifier,
        onPasswordEdited = model::onPasswordEdited,
        onFingerprintWantedChange = model::onFingerprintWantedChange,
        onBack = { onBack?.invoke() },
        onCancelFill = { onCancelFill?.invoke() },
        onSyncNow = model::onSyncNow,
        onChooseSource = onChooseSource,
        message = message,
        onMessageDismissed = messages::dismiss,
    )
}
