package de.lulurobb.keypassmobile.ui.picker

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.lulurobb.keypassmobile.autofill.AutofillRequest
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.platform.KpmPlatform
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.KpmPlatformAdapter
import de.lulurobb.keypassmobile.state.LockTracker
import de.lulurobb.keypassmobile.state.TimeSource
import de.lulurobb.keypassmobile.state.lockMessage
import de.lulurobb.keypassmobile.ui.unlock.UnlockModel
import kotlinx.coroutines.launch

/**
 * Holds the state of the autofill picker over configuration changes: the platform adapter,
 * the unlock and the picker. The request is the one of the intent that started the picker.
 */
class PickerViewModel(app: Application, val request: AutofillRequest) : ViewModel() {

    val platform = KpmPlatformAdapter(app, KpmPlatform.get(app), viewModelScope)

    val messages = AppMessages()

    val unlock = UnlockModel(
        platform,
        viewModelScope,
        TimeSource(),
        messages,
        fillTarget = request.target,
        firstRun = false,
    )

    /**
     * Set by the activity while it exists (not kept longer, so the activity does not leak).
     * Reveals the password and ends the request.
     */
    var fillHandler: ((VaultEntry) -> Unit)? = null

    val picker = PickerModel(
        platform,
        viewModelScope,
        request.target,
        request.browserLabel,
        onFill = { entry -> fillHandler?.invoke(entry) },
    )

    init {
        // The screen follows the session. Back to the start of the unlock and the reason of a
        // lock that happened while the picker was open.
        viewModelScope.launch {
            val tracker = LockTracker(platform.session.value)
            platform.session.collect { session ->
                tracker.onSession(session)?.let { locked ->
                    unlock.reset()
                    lockMessage(locked.reason)?.let(messages::post)
                }
            }
        }
    }
}
