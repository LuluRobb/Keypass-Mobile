package de.lulurobb.keypassmobile.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import de.lulurobb.keypassmobile.state.AppMessages
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * Server form (setup step 1 or "Quelle ändern"). [onBack] is the back arrow and
 * system back of the change modes; null in the first run (no arrow, system back
 * is the caller's).
 */
@Composable
fun ServerRoute(
    model: SourceFlowModel,
    messages: AppMessages,
    onBack: (() -> Unit)?,
) {
    val form by model.form.collectAsState()
    val message by messages.message.collectAsState()
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    // The typed password never reaches the model; its length tells that it was edited (an old error no longer applies).
    LaunchedEffect(model) {
        snapshotFlow { model.password.length }.distinctUntilChanged().drop(1).collect { model.onPasswordChange() }
    }
    ServerScreen(
        state = form,
        password = model.password,
        onHostChange = model::onHostChange,
        onUserChange = model::onUserChange,
        onPortChange = model::onPortChange,
        onDomainChange = model::onDomainChange,
        onToggleAdvanced = model::onToggleAdvanced,
        onConnect = model::onConnect,
        onBack = onBack ?: {},
        onAcceptOtherServer = model::onAcceptOtherServer,
        message = message,
        onMessageDismissed = messages::dismiss,
    )
}

/**
 * File browser (setup step 2 or "Quelle ändern"). Top bar arrow and system back
 * go one folder up, from the share list to the server form (both inside the
 * model); [onLeave] only runs when the model has nothing to go back to.
 */
@Composable
fun BrowseRoute(
    model: SourceFlowModel,
    messages: AppMessages,
    onLeave: () -> Unit,
) {
    val browse by model.browse.collectAsState()
    val message by messages.message.collectAsState()
    val back: () -> Unit = { if (!model.onBack()) onLeave() }
    BackHandler(onBack = back)
    browse?.let {
        BrowseScreen(
            state = it,
            onBack = back,
            onOpenCrumb = model::onOpenCrumb,
            onOpenShare = model::onOpenShare,
            onOpenFolder = model::onOpenFolder,
            onSelectFile = model::onSelectFile,
            onUseFile = model::onUseFile,
            onOpenShareDialog = model::onOpenShareDialog,
            onShareDialogNameChange = model::onShareDialogNameChange,
            onShareDialogConfirm = model::onShareDialogConfirm,
            onShareDialogDismiss = model::onShareDialogDismiss,
            onRetry = model::onRetry,
            message = message,
            onMessageDismissed = messages::dismiss,
        )
    }
}

/**
 * Setup step 4. The system pages are activity-only, so the caller passes
 * them in; call [SystemSetupModel.refresh] when the activity resumes.
 */
@Composable
fun SystemSetupRoute(
    model: SystemSetupModel,
    messages: AppMessages,
    onOpenAutofillSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val state by model.state.collectAsState()
    val message by messages.message.collectAsState()
    SystemSetupScreen(
        state = state,
        onChooseAutofill = onOpenAutofillSettings,
        onAllowNotifications = onRequestNotifications,
        onFinish = model::onFinish,
        message = message,
        onMessageDismissed = messages::dismiss,
    )
}
