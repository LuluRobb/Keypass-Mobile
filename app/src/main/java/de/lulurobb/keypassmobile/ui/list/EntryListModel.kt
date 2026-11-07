package de.lulurobb.keypassmobile.ui.list

import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.AppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.TimeSource
import de.lulurobb.keypassmobile.state.sourceKindOf
import de.lulurobb.keypassmobile.state.syncResultMessage
import de.lulurobb.keypassmobile.state.toListBanner
import de.lulurobb.keypassmobile.state.toOtherServerInfo
import de.lulurobb.keypassmobile.state.toSyncState
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.dialogs.OtherServerInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What [EntryListScreen] shows, with the vault the icons belong to (for rememberEntryIcons). */
class EntryListView(val vault: Vault, val state: EntryListState)

/**
 * State holder of the entry list (UI-01, SY-08). Works only on an unlocked session:
 * [view] is null otherwise and query, filter and the dismissed banner are reset,
 * so nothing of an old vault stays behind. Search and grouping run on
 * [computeDispatcher]; [query] itself is immediate so the text field never lags.
 */
class EntryListModel(
    private val platform: AppPlatform,
    private val scope: CoroutineScope,
    private val time: TimeSource,
    private val messages: AppMessages,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableQuery = MutableStateFlow("")
    private val mutableGroupFilter = MutableStateFlow<String?>(null)
    private val bannerDismissed = MutableStateFlow(false)
    private val serverDialogOpen = MutableStateFlow(false)

    /** Text of the search field, updated synchronously. */
    val query: StateFlow<String> = mutableQuery

    /** Selected top group, null = "Alle". */
    val groupFilter: StateFlow<String?> = mutableGroupFilter

    /** Result of one search or grouping run; the filter is the one actually applied. */
    private class Computed(
        val unlocked: SessionView.Unlocked,
        val filter: String?,
        val topGroups: List<String>,
        val content: EntryListContent,
    )

    private val computed: StateFlow<Computed?> =
        combine(platform.session, mutableQuery, mutableGroupFilter) { session, query, filter ->
            (session as? SessionView.Unlocked)?.let { compute(it, query, filter) }
        }
            .conflate()
            .flowOn(computeDispatcher)
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** The list screen, null while the session is not unlocked. */
    val view: StateFlow<EntryListView?> =
        combine(computed, mutableQuery, bannerDismissed, platform.sync, platform.settings) { c, query, dismissed, info, settings ->
            c?.let {
                val source = settings.source
                EntryListView(
                    it.unlocked.vault,
                    EntryListState(
                        query = query,
                        topGroups = it.topGroups,
                        groupFilter = it.filter,
                        sync = toSyncState(info, it.unlocked, time, sourceKindOf(settings)),
                        content = it.content,
                        rootGroupName = it.unlocked.vault.rootGroupName,
                        banner = toListBanner(info, it.unlocked, dismissed, time, source),
                    ),
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Content of the "Anderer Server" dialog, null while it is closed or no server question is pending. */
    val serverDialog: StateFlow<OtherServerInfo?> =
        combine(serverDialogOpen, platform.sync) { open, info ->
            if (open) info.serverChange?.let(::toOtherServerInfo) else null
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            platform.session.collect { session ->
                if (session !is SessionView.Unlocked) {
                    mutableQuery.value = ""
                    mutableGroupFilter.value = null
                    bannerDismissed.value = false
                    serverDialogOpen.value = false
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        mutableQuery.value = value
    }

    fun onGroupFilterChange(group: String?) {
        mutableGroupFilter.value = group
    }

    fun onLock() {
        platform.lock()
    }

    /** Sync chip. A run already in progress is not started twice. */
    fun onSyncNow() {
        if (platform.sync.value.syncing) return
        scope.launch {
            val kind = sourceKindOf(platform.settings.value)
            syncResultMessage(platform.syncNow(), kind)?.let(messages::post)
        }
    }

    fun onBannerDetails() {
        serverDialogOpen.value = true
    }

    /** "OK" on the rejected-version banner: hidden until the next lock. */
    fun onBannerDismiss() {
        bannerDismissed.value = true
    }

    /** Accept in the server dialog: trust the new identity and sync. */
    fun onServerAccept() {
        serverDialogOpen.value = false
        scope.launch {
            val result = platform.trustNewServer()
            val message = when (result) {
                SyncResult.UpToDate, SyncResult.Updated -> UiMessage.ServerAccepted
                else -> syncResultMessage(result, sourceKindOf(platform.settings.value))
            }
            message?.let(messages::post)
        }
    }

    /** Reject or dismiss in the server dialog: the banner stays. */
    fun onServerReject() {
        serverDialogOpen.value = false
    }

    private fun compute(unlocked: SessionView.Unlocked, query: String, requestedFilter: String?): Computed {
        val index = unlocked.index
        val topGroups = index.topGroups()
        // A filter whose group vanished with a new version counts as "Alle".
        val filter = requestedFilter?.takeIf { it in topGroups }
        val sections = index.sections(filter)
        val total = sections.sumOf { it.entries.size }
        val content = if (query.isBlank()) {
            EntryListContent.Grouped(sections, total)
        } else {
            EntryListContent.Search(index.search(query, filter), total)
        }
        return Computed(unlocked, filter, topGroups, content)
    }
}
