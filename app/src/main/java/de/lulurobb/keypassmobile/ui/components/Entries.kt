package de.lulurobb.keypassmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.ui.common.displayHost
import de.lulurobb.keypassmobile.ui.common.formatStamp
import de.lulurobb.keypassmobile.ui.common.groupLabel
import de.lulurobb.keypassmobile.ui.icons.EntryIcon
import de.lulurobb.keypassmobile.ui.icons.EntryIcons
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons
import java.time.LocalDateTime

/** Which version of the database is open (SY-08). */
sealed interface SyncStatus {
    /** The current version from the share or the file, loaded at [loadedAt] (null: no confirmed sync, no date shown). */
    data class Current(val loadedAt: LocalDateTime?) : SyncStatus

    /**
     * The offline copy is open; it is the version of [copyFrom] (null: no date known). [reason] is null
     * while a sync runs and nothing says why the copy is open: the screens show the syncing state.
     */
    data class OfflineCopy(val copyFrom: LocalDateTime?, val reason: OfflineReason?) : SyncStatus
}

/**
 * Why the offline copy is open instead of the current version. The first six reasons belong to an
 * SMB source, the last three to a file on the phone (see `offlineReason` in SyncMapping.kt).
 */
enum class OfflineReason {
    /** SMB: share not reachable (no VPN, timeout). */
    ShareUnreachable,

    /** SMB: a different server answered under the host (DS-04). */
    OtherServer,

    /** The newer file (share or phone) did not open with the master password (EN-06). */
    NewVersionRejected,

    /** SMB: the server refused the stored login (user or password changed). */
    LoginFailed,

    /** SMB: the file is gone from the share (moved, renamed or deleted). */
    FileNotFound,

    /** SMB: the user may no longer read the file on the share. */
    NoAccess,

    /** File on the phone: the file was moved, renamed or deleted (SY-09). */
    LocalFileNotFound,

    /** File on the phone: Android no longer lets the app read the file (SY-09). */
    LocalFileNoAccess,

    /**
     * File on the phone: any other technical problem (read error, damaged or cut-off file,
     * local storage). No reason text, no fix button, no list banner.
     */
    LocalFileProblem,
}

/**
 * Sync state for the list chip and the settings. [syncing]: a check or download runs now.
 * [kind] is the type of the source; it chooses the wording ("vom Share" or "aus der Datei").
 */
data class SyncState(
    val status: SyncStatus,
    val syncing: Boolean = false,
    val kind: SourceKind = SourceKind.SMB,
)

/** The chip under the list's search: version stamp, tap to sync now (SY-07, SY-08). */
@Composable
fun SyncChip(sync: SyncState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val offline = sync.status is SyncStatus.OfflineCopy
    val label = when {
        sync.syncing -> stringResource(R.string.sync_chip_syncing)
        sync.status is SyncStatus.Current -> if (sync.kind == SourceKind.FILE) {
            sync.status.loadedAt
                ?.let { stringResource(R.string.sync_chip_current_file, formatStamp(it)) }
                ?: stringResource(R.string.sync_chip_current_file_undated)
        } else {
            sync.status.loadedAt
                ?.let { stringResource(R.string.sync_chip_current, formatStamp(it)) }
                ?: stringResource(R.string.sync_chip_current_undated)
        }
        sync.status is SyncStatus.OfflineCopy -> sync.status.copyFrom
            ?.let { stringResource(R.string.sync_chip_offline, formatStamp(it)) }
            ?: stringResource(R.string.sync_chip_offline_undated)
        else -> ""
    }
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        enabled = !sync.syncing,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            if (sync.syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = scheme.primary,
                )
            } else {
                KpmIcon(
                    if (offline) KpmIcons.CloudOff else KpmIcons.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            }
        },
        colors = if (offline && !sync.syncing) {
            AssistChipDefaults.assistChipColors(
                containerColor = scheme.tertiaryContainer,
                labelColor = scheme.onTertiaryContainer,
                leadingIconContentColor = scheme.onTertiaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors(
                labelColor = scheme.onSurfaceVariant,
                leadingIconContentColor = scheme.primary,
                disabledLabelColor = scheme.onSurfaceVariant,
                disabledLeadingIconContentColor = scheme.primary,
            )
        },
        border = if (offline && !sync.syncing) null else AssistChipDefaults.assistChipBorder(enabled = true),
    )
}

/** What the second line of an entry row shows after the username. */
enum class RowDetail { Host, Group }

/**
 * One entry: icon, title, "username, host" or "username, group". [matching]
 * adds the "passt" pill (search results in the autofill picker).
 */
@Composable
fun EntryRow(
    entry: VaultEntry,
    icons: EntryIcons,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: RowDetail = RowDetail.Host,
    rootGroupName: String = "",
    matching: Boolean = false,
) {
    val second = when (detail) {
        RowDetail.Host -> displayHost(entry.url)
        RowDetail.Group -> groupLabel(entry.groupPath, rootGroupName)
    }
    val subline = when {
        entry.username.isNotBlank() && second.isNotBlank() ->
            stringResource(R.string.row_subline, entry.username, second)
        entry.username.isNotBlank() -> entry.username
        else -> second
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(entry, icons, Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subline.isNotEmpty()) {
                Text(
                    subline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (matching) Pill(stringResource(R.string.picker_match_badge))
    }
}

/**
 * Sticky group heading (UI-01): parent path in the variant color, the group
 * itself in primary, entry count at the end.
 */
@Composable
fun GroupHeader(groupPath: List<String>, rootGroupName: String, count: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val text = buildAnnotatedString {
        if (groupPath.size > 1) {
            withStyle(SpanStyle(color = scheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                append(groupPath.dropLast(1).joinToString(" / "))
                append(" / ")
            }
        }
        append(groupPath.lastOrNull() ?: rootGroupName)
    }
    // The count shows as a bare number; screen readers get the plural form.
    val countLabel = pluralStringResource(R.plurals.list_count, count, count)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            count.toString(),
            modifier = Modifier.semantics { contentDescription = countLabel },
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = scheme.onSurfaceVariant,
        )
    }
}

/** Grey line above the list: the total, or "x of y" while searching. */
@Composable
fun CountLine(shown: Int, total: Int, searching: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (searching) {
            pluralStringResource(R.plurals.list_count_of, total, shown, total)
        } else {
            pluralStringResource(R.plurals.list_count, total, total)
        },
        modifier = modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Quiet sentence where a list has nothing to show. */
@Composable
fun EmptyLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = ScreenPadding, vertical = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
