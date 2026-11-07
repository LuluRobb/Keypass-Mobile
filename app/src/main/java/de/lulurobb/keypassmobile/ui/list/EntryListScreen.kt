package de.lulurobb.keypassmobile.ui.list

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.model.EntrySection
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.SourceAction
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.CountLine
import de.lulurobb.keypassmobile.ui.components.EmptyLine
import de.lulurobb.keypassmobile.ui.components.EntryRow
import de.lulurobb.keypassmobile.ui.components.GroupHeader
import de.lulurobb.keypassmobile.ui.components.RowDetail
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SearchField
import de.lulurobb.keypassmobile.ui.components.SyncChip
import de.lulurobb.keypassmobile.ui.components.SyncState
import de.lulurobb.keypassmobile.ui.icons.EntryIcons
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

/** What the list shows (UI-01). Counts refer to the current group filter. */
sealed interface EntryListContent {
    /** No search: one section per group, A to Z inside. */
    data class Grouped(val sections: List<EntrySection>, val total: Int) : EntryListContent

    /** Search: one flat list, the group in each row. [total] is the count before the search. */
    data class Search(val results: List<VaultEntry>, val total: Int) : EntryListContent
}

data class EntryListState(
    val query: String,
    /** Top-level groups for the filter chips, in display order. */
    val topGroups: List<String>,
    /** Selected top group, null = "Alle". */
    val groupFilter: String?,
    val sync: SyncState,
    val content: EntryListContent,
    /** Name of the root group, shown for entries directly in it. */
    val rootGroupName: String,
    val banner: ListBanner? = null,
)

/** Entry list of the app (UI-01, UI-05, SY-08). */
@Composable
fun EntryListScreen(
    state: EntryListState,
    icons: EntryIcons,
    onQueryChange: (String) -> Unit,
    onGroupFilterChange: (String?) -> Unit,
    onEntryClick: (VaultEntry) -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
    onBannerDetails: () -> Unit = {},
    onBannerDismiss: () -> Unit = {},
    onSourceAction: (SourceAction) -> Unit = {},
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
    onMessageAction: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Column(
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(start = ScreenPadding, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SearchField(state.query, onQueryChange, Modifier.weight(1f))
                    val actionTint = MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton(onClick = onLock) {
                        KpmIcon(KpmIcons.Lock, stringResource(R.string.action_lock), tint = actionTint)
                    }
                    IconButton(onClick = onSettings) {
                        KpmIcon(KpmIcons.Settings, stringResource(R.string.action_settings), tint = actionTint)
                    }
                }
                SyncChip(
                    state.sync,
                    onClick = onSyncNow,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
                GroupChips(state.topGroups, state.groupFilter, onGroupFilterChange)
                state.banner?.let { ListBannerNotice(it, onBannerDetails, onBannerDismiss, onSourceAction) }
            }
        },
        // The Scaffold knows only the horizontal insets here (the content scrolls behind the system bars),
        // so the snackbar lifts itself above the navigation bar and the keyboard.
        snackbarHost = {
            MessageHost(
                message,
                onMessageDismissed,
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                onAction = onMessageAction,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = rememberLazyListState(),
            contentPadding = bottom,
        ) {
            entryListItems(state, icons, onEntryClick)
        }
    }
}

private fun LazyListScope.entryListItems(
    state: EntryListState,
    icons: EntryIcons,
    onEntryClick: (VaultEntry) -> Unit,
) {
    when (val content = state.content) {
        is EntryListContent.Grouped -> {
            if (content.sections.isEmpty()) {
                item(key = "empty") { EmptyLine(stringResource(R.string.list_empty_vault)) }
                return
            }
            item(key = "count") { CountLine(content.total, content.total, searching = false) }
            groupedSections(content.sections, state.rootGroupName, icons, keyPrefix = "") { onEntryClick(it) }
        }
        is EntryListContent.Search -> {
            if (content.results.isEmpty()) {
                item(key = "empty") {
                    EmptyLine(stringResource(R.string.list_no_match, state.query.trim()))
                }
                return
            }
            item(key = "count") { CountLine(content.results.size, content.total, searching = true) }
            items(content.results, key = { "s/${it.id}" }) { entry ->
                EntryRow(
                    entry,
                    icons,
                    onClick = { onEntryClick(entry) },
                    detail = RowDetail.Group,
                    rootGroupName = state.rootGroupName,
                )
            }
        }
    }
}

/** Sections with sticky group headings; shared with the autofill picker. */
internal fun LazyListScope.groupedSections(
    sections: List<EntrySection>,
    rootGroupName: String,
    icons: EntryIcons,
    keyPrefix: String,
    onEntryClick: (VaultEntry) -> Unit,
) {
    sections.forEach { section ->
        val sectionKey = keyPrefix + "g/" + section.groupPath.joinToString("\u0001")
        stickyHeader(key = sectionKey) {
            GroupHeader(section.groupPath, rootGroupName, section.entries.size)
        }
        items(section.entries, key = { keyPrefix + "e/" + it.id }) { entry ->
            EntryRow(entry, icons, onClick = { onEntryClick(entry) })
        }
    }
}

@Composable
private fun GroupChips(groups: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    if (groups.isEmpty()) return
    val label = stringResource(R.string.filter_by_group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding)
            .padding(bottom = 4.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (listOf<String?>(null) + groups).forEach { group ->
            val isSelected = group == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(group) },
                label = { Text(group ?: stringResource(R.string.filter_all)) },
                leadingIcon = if (isSelected) {
                    { KpmIcon(KpmIcons.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}
