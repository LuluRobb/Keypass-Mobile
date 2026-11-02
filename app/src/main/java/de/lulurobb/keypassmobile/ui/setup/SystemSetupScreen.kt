package de.lulurobb.keypassmobile.ui.setup

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.BigButton
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SetupStep
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

data class SystemSetupState(
    /** KeyPass Mobile is the selected autofill service (AF-01). */
    val autofillSelected: Boolean,
    /** POST_NOTIFICATIONS granted, needed to see "KeyPass Mobile entsperrt" (SE-03). */
    val notificationsAllowed: Boolean,
    val step: SetupStep = SetupStep(4),
)

/**
 * Last setup step: two settings only Android can change. Each opens a system
 * page (autofill service picker, notification permission); the caller
 * refreshes the state when the user comes back.
 */
@Composable
fun SystemSetupScreen(
    state: SystemSetupState,
    onChooseAutofill: () -> Unit,
    onAllowNotifications: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = { KpmTopBar(title = stringResource(R.string.setup_title), step = state.step) },
        snackbarHost = { MessageHost(message, onMessageDismissed) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding),
            ) {
                Text(
                    stringResource(R.string.system_heading),
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.system_lead),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SystemStep(
                    icon = KpmIcons.Keyboard,
                    title = stringResource(R.string.system_autofill_title),
                    text = stringResource(R.string.system_autofill_text),
                    done = state.autofillSelected,
                    doneText = stringResource(R.string.system_autofill_done),
                    button = stringResource(R.string.system_autofill_button),
                    onClick = onChooseAutofill,
                    modifier = Modifier.padding(top = 28.dp),
                )
                SystemStep(
                    icon = KpmIcons.Notifications,
                    title = stringResource(R.string.system_notifications_title),
                    text = stringResource(R.string.system_notifications_text),
                    done = state.notificationsAllowed,
                    doneText = stringResource(R.string.system_notifications_done),
                    button = stringResource(R.string.system_notifications_button),
                    onClick = onAllowNotifications,
                    modifier = Modifier.padding(top = 28.dp, bottom = 16.dp),
                )
            }
            Column(
                Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BigButton(text = stringResource(R.string.system_finish), onClick = onFinish)
            }
        }
    }
}

@Composable
private fun SystemStep(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    done: Boolean,
    doneText: String,
    button: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        KpmIcon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (done) {
                Row(
                    modifier = Modifier.heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KpmIcon(KpmIcons.CheckCircle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                    Text(doneText, style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
                }
            } else {
                FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(button)
                }
            }
        }
    }
}
