package de.lulurobb.keypassmobile.ui.setup

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.NavAction
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SetupStep
import de.lulurobb.keypassmobile.ui.components.SettingsRow
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

/**
 * Setup step 1 (DS-08): where the database lies. Two outlined option cards, "SMB-Freigabe" opens the
 * server form, "Datei auf dem Handy" opens the file picker of Android. No back arrow: it is the root of
 * the first run. [message] is the snackbar of a rejected file (its action is [onMessageAction]).
 */
@Composable
fun ChooseScreen(
    onSmb: () -> Unit,
    onFile: () -> Unit,
    modifier: Modifier = Modifier,
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
    onMessageAction: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = { KpmTopBar(title = stringResource(R.string.setup_title), step = SetupStep(1)) },
        snackbarHost = { MessageHost(message, onMessageDismissed, onAction = onMessageAction) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
        ) {
            Text(
                stringResource(R.string.setup_choose_heading),
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.setup_choose_lead),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionCard(
                    icon = KpmIcons.Server,
                    title = stringResource(R.string.source_kind_smb),
                    text = stringResource(R.string.setup_choose_smb_text),
                    onClick = onSmb,
                )
                OptionCard(
                    icon = KpmIcons.File,
                    title = stringResource(R.string.source_kind_file),
                    text = stringResource(R.string.setup_choose_file_text),
                    onClick = onFile,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * "Quelle verwalten" (DS-09): a plain list of exactly two rows, the current source first: change it, or
 * switch to the other kind. No sublines and no headings. [current] is the kind of the current source.
 */
@Composable
fun ManageSourceScreen(
    current: SourceKind,
    onSmb: () -> Unit,
    onFile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
    onMessageAction: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            KpmTopBar(
                title = stringResource(R.string.setup_edit_source_title),
                navigation = NavAction(KpmIcons.Back, stringResource(R.string.action_back), onBack),
            )
        },
        snackbarHost = { MessageHost(message, onMessageDismissed, onAction = onMessageAction) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            when (current) {
                SourceKind.SMB -> {
                    ManageRow(KpmIcons.Server, stringResource(R.string.manage_smb_change), onSmb)
                    ManageRow(KpmIcons.File, stringResource(R.string.manage_to_file), onFile)
                }
                SourceKind.FILE -> {
                    ManageRow(KpmIcons.File, stringResource(R.string.manage_file_change), onFile)
                    ManageRow(KpmIcons.Server, stringResource(R.string.manage_to_smb), onSmb)
                }
            }
        }
    }
}

/** One option of the first-run choice: primary icon, title, supporting text, chevron; 16 dp outlined card. */
@Composable
private fun OptionCard(@DrawableRes icon: Int, title: String, text: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, scheme.outlineVariant), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, top = 16.dp, end = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KpmIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.align(Alignment.Top).padding(top = 1.dp).size(24.dp),
            tint = scheme.primary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        KpmIcon(KpmIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun ManageRow(@DrawableRes icon: Int, title: String, onClick: () -> Unit) {
    SettingsRow(
        icon = icon,
        title = title,
        onClick = onClick,
        trailing = {
            KpmIcon(
                KpmIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
