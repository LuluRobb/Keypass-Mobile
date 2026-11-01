package de.lulurobb.keypassmobile.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.common.formatDateTime
import de.lulurobb.keypassmobile.ui.common.formatFileSize
import de.lulurobb.keypassmobile.ui.components.BigButton
import de.lulurobb.keypassmobile.ui.components.EmptyLine
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.NavAction
import de.lulurobb.keypassmobile.ui.components.Notice
import de.lulurobb.keypassmobile.ui.components.NoticeTone
import de.lulurobb.keypassmobile.ui.components.PlainField
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SetupStep
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons
import java.time.LocalDateTime

/** The server the app is connected to while browsing. */
data class ConnectedServer(
    /** NetBIOS or host name, e.g. "reitano-nas". */
    val name: String,
    /** Negotiated dialect, e.g. "SMB 3.1.1". */
    val dialect: String,
    val encrypted: Boolean,
)

data class ShareItem(val name: String, val comment: String = "")

data class KdbxFile(val name: String, val sizeBytes: Long, val modified: LocalDateTime)

/** What the browser shows at the current path. */
sealed interface BrowseContent {
    data object Loading : BrowseContent

    /** Share list of the server (path is empty). */
    data class Shares(val shares: List<ShareItem>) : BrowseContent

    /** The server did not return a share list: offer the manual entry (DS-02). */
    data object SharesNotListed : BrowseContent

    /** Folder contents: only folders and .kdbx files; [hiddenCount] other files are not shown. */
    data class Folder(
        val folders: List<String>,
        val files: List<KdbxFile>,
        val hiddenCount: Int,
    ) : BrowseContent

    /** The folder could not be listed. */
    data object Failed : BrowseContent
}

data class BrowseState(
    val server: ConnectedServer,
    /** Current location: empty = share list, else [share, folder, ...]. */
    val path: List<String>,
    val content: BrowseContent,
    /** Chosen file as full path [share, folder, ..., file], or null. */
    val selected: List<String>? = null,
    /** Null: changing the source from the settings ("Speichern" instead of step 2). */
    val step: SetupStep? = SetupStep(2),
    /** Name in the manual share dialog; null = dialog closed. */
    val shareNameDialog: String? = null,
)

/**
 * Setup step 2 (DS-02): pick share, folder and .kdbx file on the server.
 * When changing the source the caller starts with [BrowseState.path] at the
 * folder of the current file and [BrowseState.selected] at the file.
 */
@Composable
fun BrowseScreen(
    state: BrowseState,
    onBack: () -> Unit,
    onOpenCrumb: (depth: Int) -> Unit,
    onOpenShare: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSelectFile: (List<String>) -> Unit,
    onUseFile: () -> Unit,
    onOpenShareDialog: () -> Unit,
    onShareDialogNameChange: (String) -> Unit,
    onShareDialogConfirm: () -> Unit,
    onShareDialogDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            KpmTopBar(
                title = stringResource(R.string.browse_title),
                navigation = NavAction(KpmIcons.Back, stringResource(R.string.action_back), onBack),
                step = state.step,
            )
        },
        snackbarHost = { MessageHost(message, onMessageDismissed) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectedNotice(state.server)
            Crumbs(state.server.name, state.path, onOpenCrumb)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BrowseList(state, onOpenShare, onOpenFolder, onSelectFile, onOpenShareDialog, onRetry)
            }
            Column(
                Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = state.selected?.let { stringResource(R.string.browse_picked, it.joinToString(" / ")) }
                        ?: stringResource(R.string.browse_pick_hint),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                BigButton(
                    text = stringResource(if (state.step == null) R.string.browse_save else R.string.browse_use_file),
                    onClick = onUseFile,
                    enabled = state.selected != null,
                )
            }
        }
    }
    state.shareNameDialog?.let { name ->
        ShareNameDialog(name, onShareDialogNameChange, onShareDialogConfirm, onShareDialogDismiss)
    }
}

@Composable
private fun ConnectedNotice(server: ConnectedServer) {
    val security = stringResource(if (server.encrypted) R.string.browse_encrypted else R.string.browse_signed)
    val headline = stringResource(R.string.browse_connected, server.name)
    val details = stringResource(R.string.browse_connected_details, server.dialect, security)
    Notice(
        tone = NoticeTone.Positive,
        icon = KpmIcons.Check,
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(headline) }
            append(", ")
            append(details)
        },
        compact = true,
        modifier = Modifier.padding(horizontal = ScreenPadding),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Crumbs(serverName: String, path: List<String>, onOpenCrumb: (Int) -> Unit) {
    val names = listOf(serverName) + path
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding - 6.dp)
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { depth, name ->
            if (depth > 0) {
                KpmIcon(
                    KpmIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            val current = depth == names.lastIndex
            Text(
                name,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clickable(enabled = !current, role = Role.Button) { onOpenCrumb(depth) }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun BrowseList(
    state: BrowseState,
    onOpenShare: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSelectFile: (List<String>) -> Unit,
    onOpenShareDialog: () -> Unit,
    onRetry: () -> Unit,
) {
    when (val content = state.content) {
        BrowseContent.Loading -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.browse_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BrowseContent.SharesNotListed -> Column(
            Modifier.padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.browse_shares_not_listed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenShareDialog) { Text(stringResource(R.string.browse_share_manual_button)) }
        }
        BrowseContent.Failed -> Column(
            Modifier.padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.browse_folder_error),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
        is BrowseContent.Shares -> LazyColumn(Modifier.fillMaxSize()) {
            if (content.shares.isEmpty()) {
                item(key = "no-shares") { EmptyLine(stringResource(R.string.browse_no_shares)) }
            }
            items(content.shares, key = { it.name }) { share ->
                BrowseRow(
                    icon = KpmIcons.FolderShared,
                    title = share.name,
                    subtitle = share.comment.ifBlank { null },
                    chevron = true,
                    onClick = { onOpenShare(share.name) },
                )
            }
            item {
                TextButton(
                    onClick = onOpenShareDialog,
                    modifier = Modifier.padding(horizontal = ScreenPadding - 12.dp, vertical = 4.dp),
                ) { Text(stringResource(R.string.browse_share_manual)) }
            }
        }
        is BrowseContent.Folder -> LazyColumn(Modifier.fillMaxSize()) {
            items(content.folders, key = { "d/$it" }) { folder ->
                BrowseRow(
                    icon = KpmIcons.Folder,
                    title = folder,
                    chevron = true,
                    onClick = { onOpenFolder(folder) },
                )
            }
            items(content.files, key = { "f/${it.name}" }) { file ->
                val full = state.path + file.name
                val picked = state.selected == full
                BrowseRow(
                    icon = KpmIcons.Key,
                    title = file.name,
                    subtitle = stringResource(
                        R.string.browse_file_meta,
                        formatFileSize(file.sizeBytes),
                        formatDateTime(file.modified),
                    ),
                    selected = picked,
                    onClick = { onSelectFile(full) },
                )
            }
            if (content.files.isEmpty() && content.folders.isEmpty()) {
                item { EmptyLine(stringResource(R.string.browse_empty)) }
            }
            item {
                val note = buildString {
                    append(stringResource(R.string.browse_only_kdbx))
                    if (content.hiddenCount > 0) {
                        append(' ')
                        append(pluralStringResource(R.plurals.browse_hidden_files, content.hiddenCount, content.hiddenCount))
                    }
                }
                Text(
                    note,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BrowseRow(
    icon: Int,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    chevron: Boolean = false,
    selected: Boolean? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val clickModifier = if (selected != null) {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected == true) Modifier.background(scheme.secondaryContainer) else Modifier)
            .then(clickModifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            KpmIcon(icon, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            chevron -> KpmIcon(KpmIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
            selected == true -> KpmIcon(
                KpmIcons.CheckCircle,
                contentDescription = stringResource(R.string.browse_selected),
                tint = scheme.primary,
            )
        }
    }
}

/** Manual share name (DS-02 fallback when the server does not list a share). */
@Composable
fun ShareNameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.share_dialog_text))
                PlainField(
                    value = name,
                    onValueChange = onNameChange,
                    label = stringResource(R.string.share_dialog_field),
                    imeAction = ImeAction.Go,
                    onImeAction = { if (name.isNotBlank()) onConfirm() },
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.share_dialog_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
