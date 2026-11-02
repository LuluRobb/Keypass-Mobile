package de.lulurobb.keypassmobile.ui.picker

import de.lulurobb.keypassmobile.core.index.EntryIndex
import de.lulurobb.keypassmobile.core.match.EntryMatcher
import de.lulurobb.keypassmobile.model.EntrySection
import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.state.AppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.ui.dialogs.PhishingWarning
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
import java.util.UUID

/** What [PickerScreen] shows, with the vault the icons belong to (for rememberEntryIcons). */
class PickerView(val vault: Vault, val state: PickerState)

/**
 * State holder of the autofill picker (AF-03 to AF-07). Works only on an unlocked session:
 * [view] is null otherwise and query and phishing warning are reset, so nothing of an old
 * vault stays behind. Matching, grouping and search run on [computeDispatcher]; [query]
 * itself is immediate so the text field never lags.
 *
 * [onFill] is called with the entry to fill: at once for a pick that belongs to the target,
 * after "Trotzdem ausfüllen" for a web page that does not (AF-06). Apps never get the
 * warning (AF-07). The caller reveals the password and ends the request.
 */
class PickerModel(
    private val platform: AppPlatform,
    private val scope: CoroutineScope,
    private val target: FillTarget,
    private val browserLabel: String?,
    private val onFill: (VaultEntry) -> Unit,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableQuery = MutableStateFlow("")
    private val mutablePhishing = MutableStateFlow<PhishingWarning?>(null)

    /** Text of the search field, updated synchronously. */
    val query: StateFlow<String> = mutableQuery

    /** Result of one matching and search run. */
    private class Computed(
        val unlocked: SessionView.Unlocked,
        val matching: List<VaultEntry>,
        val sections: List<EntrySection>,
        val searchResults: List<VaultEntry>?,
        val matchingIds: Set<UUID>,
    )

    private val computed: StateFlow<Computed?> =
        combine(platform.session, mutableQuery) { session, query ->
            (session as? SessionView.Unlocked)?.let { compute(it, query) }
        }
            .conflate()
            .flowOn(computeDispatcher)
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** The picker screen, null while the session is not unlocked. */
    val view: StateFlow<PickerView?> =
        combine(computed, mutableQuery, mutablePhishing) { c, query, phishing ->
            c?.let {
                PickerView(
                    it.unlocked.vault,
                    PickerState(
                        target = target,
                        browserLabel = browserLabel,
                        query = query,
                        matching = it.matching,
                        sections = it.sections,
                        searchResults = it.searchResults,
                        matchingIds = it.matchingIds,
                        rootGroupName = it.unlocked.vault.rootGroupName,
                        phishing = phishing,
                    ),
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            platform.session.collect { session ->
                if (session !is SessionView.Unlocked) {
                    mutableQuery.value = ""
                    mutablePhishing.value = null
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        mutableQuery.value = value
    }

    /** Tap on an entry: fills at once, or asks first when it does not belong to the web page (AF-06). */
    fun onPick(entry: VaultEntry) {
        val web = target as? FillTarget.Web
        if (web != null && EntryMatcher.isDomainMismatch(entry, web)) {
            mutablePhishing.value = PhishingWarning(entry, EntryMatcher.displayDomain(web), EntryMatcher.displayDomain(entry))
        } else {
            onFill(entry)
        }
    }

    /** "Trotzdem ausfüllen" in the phishing warning. */
    fun onPhishingFillAnyway() {
        val warning = mutablePhishing.value ?: return
        mutablePhishing.value = null
        onFill(warning.entry)
    }

    /** "Abbrechen" or dismissing the phishing warning: back to the list. */
    fun onPhishingDismiss() {
        mutablePhishing.value = null
    }

    private fun compute(unlocked: SessionView.Unlocked, query: String): Computed {
        val index: EntryIndex = unlocked.index
        val matching = index.matching(target)
        return Computed(
            unlocked = unlocked,
            matching = matching,
            sections = index.sections(),
            searchResults = if (query.isBlank()) null else index.search(query),
            matchingIds = matching.mapTo(HashSet()) { it.id },
        )
    }
}
