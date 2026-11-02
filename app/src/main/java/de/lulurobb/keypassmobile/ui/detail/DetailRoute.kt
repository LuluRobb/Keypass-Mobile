package de.lulurobb.keypassmobile.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.rememberEntryIcons

/** Connects [EntryDetailScreen] to [model]; back is navigation of the caller. */
@Composable
fun DetailRoute(
    model: EntryDetailModel,
    messages: AppMessages,
    onBack: () -> Unit,
) {
    val view by model.view.collectAsState()
    val message by messages.message.collectAsState()
    val current = view ?: return
    val icons = rememberEntryIcons(current.vault)
    EntryDetailScreen(
        state = current.state,
        icons = icons,
        onBack = onBack,
        onLock = model::onLock,
        onCopyUsername = model::onCopyUsername,
        onCopyPassword = model::onCopyPassword,
        onTogglePassword = model::onTogglePassword,
        message = message,
        onMessageDismissed = messages::dismiss,
    )
}
