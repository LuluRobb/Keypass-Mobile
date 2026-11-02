package de.lulurobb.keypassmobile.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import de.lulurobb.keypassmobile.ui.components.SecretInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.BigButton
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.NavAction
import de.lulurobb.keypassmobile.ui.components.Notice
import de.lulurobb.keypassmobile.ui.components.NoticeTone
import de.lulurobb.keypassmobile.ui.components.PlainField
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SecretField
import de.lulurobb.keypassmobile.ui.components.SetupStep
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

/** Why connecting failed (DS-02, DS-04). */
sealed interface ConnectError {
    /** No SMB answer within the timeout (DS-05). */
    data object Unreachable : ConnectError

    /** Wrong user or password. */
    data object LoginFailed : ConnectError

    /**
     * A different server than the remembered one answered (DS-04); the app did
     * not log in. Only when changing the source of an existing setup.
     */
    data class OtherServer(val rememberedName: String) : ConnectError

    /** Only SMB 1 offered. */
    data object Unsupported : ConnectError

    /** Anything else; never carries server texts or secrets (SE-07). */
    data object Unknown : ConnectError
}

/** Fields with a validation error. */
enum class ServerField { Host, User, Port }

data class ServerFormState(
    val host: String = "",
    val user: String = "",
    val port: String = "445",
    val domain: String = "",
    val advancedOpen: Boolean = false,
    val connecting: Boolean = false,
    val invalidFields: Set<ServerField> = emptySet(),
    val error: ConnectError? = null,
    /** Null: changing the source from the settings, with back arrow and without intro. */
    val step: SetupStep? = SetupStep(1),
    /** The stored SMB password fits this address: an empty password field means "use it". */
    val storedPasswordUsable: Boolean = false,
)

/**
 * Setup step 1 and changing the source (DS-02): host, user, password, port and
 * domain under "Erweitert". The SMB password lives in [password] (caller owned).
 */
@Composable
fun ServerScreen(
    state: ServerFormState,
    password: SecretInput,
    onHostChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onDomainChange: (String) -> Unit,
    onToggleAdvanced: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onAcceptOtherServer: () -> Unit = {},
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
) {
    val editing = state.step == null
    // The sticky button would cover the next fields on the small area left above the keyboard: while the
    // keyboard is open the button is the last item of the scrolling content instead.
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardOpen by remember { derivedStateOf { ime.getBottom(density) > 0 } }
    val connectButton: @Composable () -> Unit = {
        BigButton(
            text = stringResource(if (state.connecting) R.string.setup_connecting else R.string.setup_connect),
            onClick = onConnect,
            loading = state.connecting,
        )
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            KpmTopBar(
                title = stringResource(if (editing) R.string.setup_edit_source_title else R.string.setup_title),
                navigation = if (editing) NavAction(KpmIcons.Back, stringResource(R.string.action_back), onBack) else null,
                step = state.step,
            )
        },
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
                if (!editing) {
                    Text(
                        stringResource(R.string.setup_server_heading),
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp).semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(R.string.setup_server_lead),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(12.dp))
                val enabled = !state.connecting
                PlainField(
                    value = state.host,
                    onValueChange = onHostChange,
                    label = stringResource(R.string.setup_field_host),
                    helper = stringResource(R.string.setup_field_host_helper),
                    error = if (ServerField.Host in state.invalidFields) stringResource(R.string.setup_error_host_missing) else null,
                    keyboardType = KeyboardType.Uri,
                    enabled = enabled,
                    modifier = Modifier.padding(top = 8.dp),
                )
                PlainField(
                    value = state.user,
                    onValueChange = onUserChange,
                    label = stringResource(R.string.setup_field_user),
                    helper = stringResource(R.string.setup_field_user_helper),
                    error = if (ServerField.User in state.invalidFields) stringResource(R.string.setup_error_user_missing) else null,
                    enabled = enabled,
                    modifier = Modifier.padding(top = 8.dp),
                )
                SecretField(
                    input = password,
                    label = stringResource(R.string.setup_field_password),
                    enabled = enabled,
                    supportingText = if (state.storedPasswordUsable) stringResource(R.string.setup_field_password_keep) else null,
                    imeAction = if (state.advancedOpen) ImeAction.Next else ImeAction.Done,
                    modifier = Modifier.padding(top = 8.dp),
                )
                AdvancedToggle(open = state.advancedOpen, onClick = onToggleAdvanced)
                AnimatedVisibility(visible = state.advancedOpen) {
                    Column {
                        PlainField(
                            value = state.port,
                            onValueChange = onPortChange,
                            label = stringResource(R.string.setup_field_port),
                            error = if (ServerField.Port in state.invalidFields) stringResource(R.string.setup_error_port_invalid) else null,
                            keyboardType = KeyboardType.Number,
                            enabled = enabled,
                        )
                        PlainField(
                            value = state.domain,
                            onValueChange = onDomainChange,
                            label = stringResource(R.string.setup_field_domain),
                            enabled = enabled,
                            imeAction = ImeAction.Done,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                state.error?.let { ConnectErrorNotice(it, state.host, onAcceptOtherServer) }
                Spacer(Modifier.size(16.dp))
                if (keyboardOpen) {
                    connectButton()
                    Spacer(Modifier.size(16.dp))
                }
            }
            if (!keyboardOpen) {
                Column(Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp, bottom = 24.dp)) {
                    connectButton()
                }
            }
        }
    }
}

@Composable
private fun AdvancedToggle(open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.setup_advanced),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        KpmIcon(
            if (open) KpmIcons.ExpandLess else KpmIcons.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ConnectErrorNotice(error: ConnectError, host: String, onAcceptOtherServer: () -> Unit) {
    val (title, text) = when (error) {
        ConnectError.Unreachable -> stringResource(R.string.setup_error_unreachable_title) to
            stringResource(R.string.setup_error_unreachable_text, host)
        ConnectError.LoginFailed -> stringResource(R.string.setup_error_login_title) to
            stringResource(R.string.setup_error_login_text)
        is ConnectError.OtherServer -> stringResource(R.string.setup_error_other_server_title, host) to
            stringResource(R.string.setup_error_other_server_text, error.rememberedName)
        ConnectError.Unsupported -> stringResource(R.string.setup_error_unsupported_title) to
            stringResource(R.string.setup_error_unsupported_text)
        ConnectError.Unknown -> stringResource(R.string.setup_error_unknown_title) to
            stringResource(R.string.setup_error_unknown_text)
    }
    Notice(
        tone = NoticeTone.Error,
        icon = if (error is ConnectError.OtherServer) KpmIcons.Server else KpmIcons.Error,
        title = title,
        text = text,
        modifier = Modifier.padding(top = 16.dp),
        actions = if (error is ConnectError.OtherServer) {
            {
                TextButton(
                    onClick = onAcceptOtherServer,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text(stringResource(R.string.setup_error_other_server_accept)) }
            }
        } else {
            null
        },
    )
}
