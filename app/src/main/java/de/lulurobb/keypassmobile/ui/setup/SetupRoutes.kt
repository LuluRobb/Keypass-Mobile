package de.lulurobb.keypassmobile.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.ui.dialogs.OlderVersionDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * The source choice: setup step 1 (two option cards, no arrow) in the first run, "Quelle verwalten"
 * (two rows, current source first) in the change modes. [onBack] is the back arrow and system back of
 * the change modes; null in the first run (no arrow, system back is the caller's). [onMessageAction]
 * is "Neu wählen" of a rejected file.
 */
@Composable
fun ChooseRoute(
    model: SourceFlowModel,
    messages: AppMessages,
    onBack: (() -> Unit)?,
    onMessageAction: () -> Unit,
) {
    val message by messages.message.collectAsState()
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val current: SourceKind? = model.manageKind
    if (current == null || onBack == null) {
        ChooseScreen(
            onSmb = model::onChooseSmb,
            onFile = model::onChooseFile,
            message = message,
            onMessageDismissed = messages::dismiss,
            onMessageAction = onMessageAction,
        )
    } else {
        ManageSourceScreen(
            current = current,
            onSmb = model::onChooseSmb,
            onFile = model::onChooseFile,
            onBack = onBack,
            message = message,
            onMessageDismissed = messages::dismiss,
            onMessageAction = onMessageAction,
        )
    }
}

/**
 * Server form (setup step 1 after the choice, or "Quelle verwalten"). [onBack] is the back arrow and
 * system back: in the first run it goes back to the choice.
 */
@Composable
fun ServerRoute(
    model: SourceFlowModel,
    messages: AppMessages,
    onBack: () -> Unit,
) {
    val form by model.form.collectAsState()
    val message by messages.message.collectAsState()
    BackHandler(onBack = onBack)
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
        onBack = onBack,
        onAcceptOtherServer = model::onAcceptOtherServer,
        message = message,
        onMessageDismissed = messages::dismiss,
    )
}

/**
 * File browser (setup step 2 or "Quelle verwalten"). Top bar arrow and system back
 * go one folder up, from the share list to the server form (both inside the
 * model); [onLeave] only runs when the model has nothing to go back to.
 * Shows the "Ältere Version" dialog of the SMB file (DS-14).
 */
@Composable
fun BrowseRoute(
    model: SourceFlowModel,
    messages: AppMessages,
    onLeave: () -> Unit,
) {
    val browse by model.browse.collectAsState()
    val message by messages.message.collectAsState()
    val older by model.olderDialog.collectAsState()
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
    older?.let { info ->
        OlderVersionDialog(info, onUseAnyway = model::onOlderUseAnyway, onCancel = model::onOlderCancel)
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
