package de.lulurobb.keypassmobile.ui.licenses

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the list stands: the data is read off the main thread, so there is a moment before it exists. */
sealed interface LicenseListState {
    data object Loading : LicenseListState

    data object Failed : LicenseListState

    data class Loaded(val entries: List<LicenseEntry>) : LicenseListState
}

/**
 * The licenses of the app, loaded by [load] (a read of the raw resource off the main thread) and kept
 * as long as the catalog instance lives. The app creates it in the AppRoot composition, so it stays
 * while the process shows the app (the list is there at once when a detail page goes back) and is
 * read again after the activity was recreated (rotation, theme change).
 */
@Stable
class LicenseCatalog(private val load: () -> List<LicenseEntry>) {
    var state: LicenseListState by mutableStateOf(LicenseListState.Loading)
        private set

    /** Loads the entries unless they are loaded; a failed load is tried again on the next call. */
    suspend fun ensureLoaded() {
        if (state is LicenseListState.Loaded) return
        state = try {
            LicenseListState.Loaded(withContext(Dispatchers.IO) { load() })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LicenseListState.Failed
        }
    }

    /** The entry with [id]; null while loading, when the load failed or the id is unknown. */
    fun find(id: String): LicenseEntry? = (state as? LicenseListState.Loaded)?.entries?.firstOrNull { it.id == id }
}
