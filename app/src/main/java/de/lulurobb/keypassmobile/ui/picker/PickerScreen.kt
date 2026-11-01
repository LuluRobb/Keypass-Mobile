package de.lulurobb.keypassmobile.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.model.EntrySection
import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.ui.common.breakAfter
import de.lulurobb.keypassmobile.ui.components.EmptyLine
import de.lulurobb.keypassmobile.ui.components.EntryRow
import de.lulurobb.keypassmobile.ui.components.Notice
import de.lulurobb.keypassmobile.ui.components.NoticeTone
import de.lulurobb.keypassmobile.ui.components.RowDetail
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SearchField
import de.lulurobb.keypassmobile.ui.components.SectionLabel
import de.lulurobb.keypassmobile.ui.dialogs.PhishingDialog
import de.lulurobb.keypassmobile.ui.dialogs.PhishingWarning
import de.lulurobb.keypassmobile.ui.icons.EntryIcons
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons
import de.lulurobb.keypassmobile.ui.list.groupedSections
import java.util.UUID

data class PickerState(
    val target: FillTarget,
    /** App name of the browser for web targets ("Brave"), null when unknown. */
    val browserLabel: String?,
    val query: String,
    /** Entries matching the target (AF-05), shown first. */
    val matching: List<VaultEntry>,
    /** All entries by group (UI-01). */
    val sections: List<EntrySection>,
    /** Flat search results; null while the query is blank. */
    val searchResults: List<VaultEntry>?,
    /** Ids of matching entries, for the "passt" pill in search results. */
    val matchingIds: Set<UUID>,
    val rootGroupName: String,
    /** Open phishing warning (AF-06), null when none. */
    val phishing: PhishingWarning? = null,
)

/**
 * Full-screen autofill picker (AF-03 to AF-07): where it fills, search,
 * matching entries on top, all entries below. A tap picks; the caller decides
 * whether to fill at once or to show [PickerState.phishing] first.
 */
@Composable
fun PickerScreen(
    state: PickerState,
    icons: EntryIcons,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onPick: (VaultEntry) -> Unit,
    onPhishingFillAnyway: () -> Unit,
    onPhishingDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Column(
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            ) {
                TargetHeader(state, onClose)
                SearchField(
                    state.query,
                    onQueryChange,
                    Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
        ) {
            val results = state.searchResults
            if (results != null) {
                if (results.isEmpty()) {
                    item(key = "empty") { EmptyLine(stringResource(R.string.list_no_match, state.query.trim())) }
                } else {
                    items(results, key = { "s/${it.id}" }) { entry ->
                        EntryRow(
                            entry,
                            icons,
                            onClick = { onPick(entry) },
                            detail = RowDetail.Group,
                            rootGroupName = state.rootGroupName,
                            matching = entry.id in state.matchingIds,
                        )
                    }
                }
            } else {
                item(key = "match-head") { SectionLabel(matchingTitle(state), topPadding = 12.dp) }
                if (state.matching.isEmpty()) {
                    item(key = "no-match") { NoMatch(state.target) }
                } else {
                    items(state.matching, key = { "m/${it.id}" }) { entry ->
                        EntryRow(
                            entry,
                            icons,
                            onClick = { onPick(entry) },
                            detail = RowDetail.Group,
                            rootGroupName = state.rootGroupName,
                        )
                    }
                }
                item(key = "all-head") { SectionLabel(stringResource(R.string.picker_all_entries), topPadding = 12.dp) }
                groupedSections(state.sections, state.rootGroupName, icons, keyPrefix = "a/") { onPick(it) }
            }
        }
    }
    state.phishing?.let { PhishingDialog(it, onPhishingFillAnyway, onPhishingDismiss) }
}

@Composable
private fun TargetHeader(state: PickerState, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = ScreenPadding, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onClose) { KpmIcon(KpmIcons.Close, stringResource(R.string.action_cancel)) }
        Column(
            Modifier.weight(1f).padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.picker_fill_into),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            when (val target = state.target) {
                is FillTarget.Web -> {
                    Text(target.host, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                    Text(
                        if (state.browserLabel != null) {
                            stringResource(R.string.picker_web_sub, state.browserLabel, target.browserPackage)
                        } else {
                            target.browserPackage
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                is FillTarget.App -> {
                    // The package name is what the user is shown (AF-07); monospace keeps
                    // look-alike characters apart.
                    Text(
                        breakAfter(target.packageName, "."),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = scheme.onSurface,
                    )
                    Text(
                        target.appLabel?.let { stringResource(R.string.picker_app_sub, it) }
                            ?: stringResource(R.string.picker_app_sub_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun matchingTitle(state: PickerState): String = when (val target = state.target) {
    is FillTarget.Web -> stringResource(R.string.picker_matching_web, target.host)
    is FillTarget.App -> target.appLabel?.let { stringResource(R.string.picker_matching_app, it) }
        ?: stringResource(R.string.picker_matching_app_unknown)
}

@Composable
private fun NoMatch(target: FillTarget) {
    val web = target is FillTarget.Web
    Notice(
        tone = if (web) NoticeTone.Error else NoticeTone.Neutral,
        icon = if (web) KpmIcons.Warning else KpmIcons.Info,
        text = stringResource(if (web) R.string.picker_no_match_web else R.string.picker_no_match_app),
        compact = true,
        modifier = Modifier.padding(horizontal = ScreenPadding).padding(top = 6.dp, bottom = 8.dp),
    )
}
