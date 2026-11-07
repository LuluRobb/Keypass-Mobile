package de.lulurobb.keypassmobile.ui.licenses

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.lulurobb.keypassmobile.state.AppMessages

/** The licenses list on the shared [catalog]; it loads the data when it is not there yet. */
@Composable
fun LicensesRoute(catalog: LicenseCatalog, onBack: () -> Unit, onOpen: (LicenseEntry) -> Unit) {
    LaunchedEffect(catalog) { catalog.ensureLoaded() }
    LicensesScreen(state = catalog.state, onBack = onBack, onOpen = onOpen)
}

/**
 * The detail page of the library [id] on the shared [catalog]. The links are activity-only, so the
 * caller passes [onOpenLink] in; "no browser" is shown from the shared [messages].
 */
@Composable
fun LicenseDetailRoute(
    catalog: LicenseCatalog,
    id: String,
    messages: AppMessages,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    LaunchedEffect(catalog) { catalog.ensureLoaded() }
    val message by messages.message.collectAsState()
    LicenseDetailScreen(
        entry = catalog.find(id),
        onBack = onBack,
        onOpenLink = onOpenLink,
        message = message,
        onMessageDismissed = messages::dismiss,
    )
}
