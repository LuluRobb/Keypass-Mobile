package de.lulurobb.keypassmobile.ui.detail

import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.state.AppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** What [EntryDetailScreen] shows, with the vault the icons belong to (for rememberEntryIcons). */
class EntryDetailView(val vault: Vault, val state: EntryDetailState)

/**
 * State holder of the copy view (UI-02, AF-09) for one entry. The entry is looked up
 * again whenever the session changes (a newer version replaces the vault); when it is
 * gone, [onMissing] is called once and [view] turns null. The copy messages come
 * from the clipboard binding of AppMessages.
 *
 * The password is hidden by default. [onTogglePassword] reveals it (a copy of the vault's
 * characters, see [EntryDetailState.shownPassword]); the copy is wiped and dropped by
 * [hidePassword], when the session locks or another vault version replaces the entry, and when
 * the model's scope ends (the user left the screen).
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

    /** The revealed password, null while hidden. Only [hidePassword] and [onTogglePassword] replace it. */
    private val shown = MutableStateFlow<CharArray?>(null)
    private var shownVault: Vault? = null

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { hidePassword() }
        scope.launch {
            combine(platform.session, platform.settings, shown) { session, settings, shownPassword ->
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
                                shownPassword = shownPassword,
                            ),
                        ),
                        missing = false,
                    )
                }
            }.collect { lookup ->
                // Locked, entry gone or a new vault: what was revealed belongs to the old state.
                if (shown.value != null && lookup.view?.vault !== shownVault) hidePassword()
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

    /** Reveals the password, or hides it again when it is shown. Nothing is shown when the vault has no password to give. */
    fun onTogglePassword() {
        if (shown.value != null) {
            hidePassword()
            return
        }
        val vault = mutableView.value?.vault ?: return
        val password = platform.revealPassword(entryId) ?: return
        shownVault = vault
        shown.value = password
    }

    /** Hides the password and wipes the revealed copy. Also for the activity's onStop. */
    fun hidePassword() {
        var old: CharArray? = null
        shown.update { current ->
            old = current
            null
        }
        old?.fill('\u0000')
    }

    fun onLock() {
        platform.lock()
    }
}
