package de.lulurobb.keypassmobile.ui.detail

import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.state.AppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/** What [EntryDetailScreen] shows, with the vault the icons belong to (for rememberEntryIcons). */
class EntryDetailView(val vault: Vault, val state: EntryDetailState)

/**
 * State holder of the copy view (UI-02, AF-09) for one entry. The entry is looked up
 * again whenever the session changes (a newer version replaces the vault); when it is
 * gone, [onMissing] is called once and [view] turns null. The copy messages come
 * from the clipboard binding of AppMessages.
 */
class EntryDetailModel(
    private val platform: AppPlatform,
    scope: CoroutineScope,
    private val entryId: UUID,
    private val onMissing: () -> Unit,
) {
    private class Lookup(val view: EntryDetailView?, val missing: Boolean)

    private val mutableView = MutableStateFlow<EntryDetailView?>(null)

    /** The entry, null while locked or after it vanished. */
    val view: StateFlow<EntryDetailView?> = mutableView

    private var missingReported = false

    init {
        scope.launch {
            combine(platform.session, platform.settings) { session, settings ->
                val unlocked = session as? SessionView.Unlocked
                val entry = unlocked?.vault?.entries?.firstOrNull { it.id == entryId }
                when {
                    unlocked == null -> Lookup(null, missing = false)
                    entry == null -> Lookup(null, missing = true)
                    else -> Lookup(
                        EntryDetailView(
                            unlocked.vault,
                            EntryDetailState(
                                entry = entry,
                                rootGroupName = unlocked.vault.rootGroupName,
                                clipboardSeconds = settings.clipboardTimeout.duration.seconds.toInt(),
                            ),
                        ),
                        missing = false,
                    )
                }
            }.collect { lookup ->
                mutableView.value = lookup.view
                if (lookup.missing && !missingReported) {
                    missingReported = true
                    onMissing()
                }
            }
        }
    }

    fun onCopyUsername() {
        platform.copyUsername(entryId)
    }

    fun onCopyPassword() {
        platform.copyPassword(entryId)
    }

    fun onLock() {
        platform.lock()
    }
}
