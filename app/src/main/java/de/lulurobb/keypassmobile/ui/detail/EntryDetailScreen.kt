package de.lulurobb.keypassmobile.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.common.groupLabel
import de.lulurobb.keypassmobile.ui.common.isPlainHttp
import de.lulurobb.keypassmobile.ui.components.BigButton
import de.lulurobb.keypassmobile.ui.components.BigButtonStyle
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.NavAction
import de.lulurobb.keypassmobile.ui.components.Notice
import de.lulurobb.keypassmobile.ui.components.NoticeTone
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.icons.EntryIcon
import de.lulurobb.keypassmobile.ui.icons.EntryIcons
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

data class EntryDetailState(
    val entry: VaultEntry,
    val rootGroupName: String,
    /**
     * The revealed password, null while hidden. The model owns and wipes the array; the screen only
     * reads it while it is shown.
     */
    val shownPassword: CharArray? = null,
)

/**
 * Copy view of one entry (UI-02, AF-09): icon, title, URL, group, username,
 * notes and the two copy buttons. The password is hidden until the eye button reveals it.
 */
@Composable
fun EntryDetailScreen(
    state: EntryDetailState,
    icons: EntryIcons,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    modifier: Modifier = Modifier,
    onTogglePassword: () -> Unit = {},
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
) {
    val entry = state.entry
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        topBar = {
            KpmTopBar(
                title = "",
                navigation = NavAction(KpmIcons.Back, stringResource(R.string.action_back), onBack),
                actions = {
                    IconButton(onClick = onLock) { KpmIcon(KpmIcons.Lock, stringResource(R.string.action_lock)) }
                },
            )
        },
        snackbarHost = { MessageHost(message, onMessageDismissed) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = scheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding),
            ) {
                Column(Modifier.padding(top = 4.dp, bottom = 20.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        EntryIcon(entry, icons, Modifier.size(32.dp), iconSize = 28.dp)
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    if (entry.url.isNotBlank()) {
                        Text(entry.url, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        KpmIcon(
                            KpmIcons.Folder,
                            contentDescription = stringResource(R.string.detail_group),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            groupLabel(entry.groupPath, state.rootGroupName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                ValueBlock(label = stringResource(R.string.detail_username)) {
                    if (entry.username.isNotBlank()) {
                        // Not selectable: copying goes through the button, so it is marked
                        // sensitive and cleared after the set time (AF-09).
                        Text(entry.username, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Text(
                            stringResource(R.string.detail_no_username),
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                ValueBlock(label = stringResource(R.string.detail_password)) {
                    PasswordValue(state.shownPassword, onTogglePassword)
                }
                if (entry.notes.isNotBlank()) {
                    ValueBlock(label = stringResource(R.string.detail_notes)) {
                        Text(entry.notes, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (isPlainHttp(entry.url)) {
                    Notice(
                        tone = NoticeTone.Neutral,
                        icon = KpmIcons.Info,
                        text = stringResource(R.string.detail_http_hint),
                        compact = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.size(16.dp))
            }
            Column(
                Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BigButton(
                    text = stringResource(R.string.detail_copy_username),
                    onClick = onCopyUsername,
                    icon = KpmIcons.Copy,
                    style = BigButtonStyle.Tonal,
                    enabled = entry.username.isNotBlank(),
                )
                BigButton(
                    text = stringResource(R.string.detail_copy_password),
                    onClick = onCopyPassword,
                    icon = KpmIcons.Copy,
                )
            }
        }
    }
}

/** The dots or, while revealed, the password in monospace; the eye button toggles (UI-02). */
@Composable
private fun PasswordValue(shown: CharArray?, onToggle: () -> Unit) {
    // The String copy lives only as long as the password is shown (the model wipes the array).
    val text = remember(shown) { shown?.let { String(it) } }
    val style = MaterialTheme.typography.bodyLarge
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (text != null) {
            Text(text, style = style.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
        } else {
            Text(
                stringResource(R.string.detail_password_dots),
                style = style.copy(letterSpacing = style.fontSize * 0.18f),
                modifier = Modifier.weight(1f),
            )
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
            KpmIcon(
                id = if (text != null) KpmIcons.VisibilityOff else KpmIcons.Visibility,
                contentDescription = stringResource(
                    if (text != null) R.string.action_hide_password else R.string.action_show_password,
                ),
            )
        }
    }
}

/** Label over value in a low tonal block (the draft's key-value well). */
@Composable
private fun ValueBlock(label: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
