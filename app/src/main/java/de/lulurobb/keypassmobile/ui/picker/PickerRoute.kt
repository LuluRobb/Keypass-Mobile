package de.lulurobb.keypassmobile.ui.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import de.lulurobb.keypassmobile.state.rememberEntryIcons

/**
 * Connects [PickerScreen] to [model]. [onClose] (close button) cancels the fill request;
 * the pick itself ends the request through the callback of the model.
 */
@Composable
fun PickerRoute(
    model: PickerModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view by model.view.collectAsState()
    val current = view ?: return
    val icons = rememberEntryIcons(current.vault)
    PickerScreen(
        state = current.state,
        icons = icons,
        onClose = onClose,
        onQueryChange = model::onQueryChange,
        onPick = model::onPick,
        onPhishingFillAnyway = model::onPhishingFillAnyway,
        onPhishingDismiss = model::onPhishingDismiss,
        modifier = modifier,
    )
}
