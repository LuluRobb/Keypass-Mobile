package de.lulurobb.keypassmobile.state

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.lulurobb.keypassmobile.platform.KpmPlatform
import de.lulurobb.keypassmobile.platform.SystemState

/**
 * Holds the [AppController] across configuration changes, so rotating or recreating the
 * activity (and the notification tap into the existing task) keeps the open screen without
 * a new unlock. [adapter] maps the session on [viewModelScope]; the fingerprint prompter
 * needs the activity and comes from [KpmPlatformAdapter.prompter].
 */
class AppViewModel(context: Context, kpm: KpmPlatform) : ViewModel() {

    val adapter = KpmPlatformAdapter(context, kpm, viewModelScope)

    val controller = AppController(adapter, viewModelScope)

    /** System settings pages and permissions, activity-only actions. */
    val system: SystemState = kpm.system

    override fun onCleared() {
        controller.close()
    }
}
