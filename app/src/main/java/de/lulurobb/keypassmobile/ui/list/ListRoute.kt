package de.lulurobb.keypassmobile.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.rememberEntryIcons
import de.lulurobb.keypassmobile.ui.common.SourceAction
import de.lulurobb.keypassmobile.ui.dialogs.OtherServerDialog
import java.util.UUID

/**
 * Connects [EntryListScreen] to [model]; entry click and settings are navigation of the caller.
 * [onSourceAction] runs the button of a source banner (UI-09, see [SourceAction]); [onMessageAction]
 * the action of a snackbar message ("Neu wählen" of a rejected file).
 */
@Composable
fun ListRoute(
    model: EntryListModel,
    messages: AppMessages,
    onEntryClick: (UUID) -> Unit,
    onSettings: () -> Unit,
    onSourceAction: (SourceAction) -> Unit = {},
    onMessageAction: () -> Unit = {},
) {
    val view by model.view.collectAsState()
    val message by messages.message.collectAsState()
    val serverInfo by model.serverDialog.collectAsState()
    val current = view ?: return
    val icons = rememberEntryIcons(current.vault)
    EntryListScreen(
        state = current.state,
        icons = icons,
        onQueryChange = model::onQueryChange,
        onGroupFilterChange = model::onGroupFilterChange,
        onEntryClick = { onEntryClick(it.id) },
        onLock = model::onLock,
        onSettings = onSettings,
        onSyncNow = model::onSyncNow,
        onBannerDetails = model::onBannerDetails,
        onBannerDismiss = model::onBannerDismiss,
        onSourceAction = onSourceAction,
        message = message,
        onMessageDismissed = messages::dismiss,
        onMessageAction = onMessageAction,
    )
    serverInfo?.let { info ->
        OtherServerDialog(info, onAccept = model::onServerAccept, onDismiss = model::onServerReject)
    }
}
